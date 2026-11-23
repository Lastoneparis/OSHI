import Foundation

// Shared transport rules for the covert relay and its receiver.
// Kept independent of UIKit so the rules can also be exercised without an app host.
enum CovertHTTP {
    enum Failure: Error { case invalidResponse, httpStatus(Int), malformedPayload, insecureURL }

    static func formValue(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))!
    }

    static func validDNSName(_ name: String) -> Bool {
        guard !name.isEmpty, name.utf8.count <= 253 else { return false }
        return name.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-" &&
            label.utf8.allSatisfy { (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
        }
    }

    static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw Failure.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw Failure.httpStatus(http.statusCode) }
    }

    static func fragmentsFromConfig(_ data: Data) throws -> [String] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let settings = object["settings"] as? [String: Any],
              let fragments = settings["data"] as? [String] else { throw Failure.malformedPayload }
        return fragments
    }

    static func fragmentsFromAnalytics(_ data: Data) throws -> [String] {
        guard let js = String(data: data, encoding: .utf8), let start = js.range(of: "/*"),
              let end = js.range(of: "*/", range: start.upperBound..<js.endIndex),
              let payload = Data(base64Encoded: String(js[start.upperBound..<end.lowerBound])) else { throw Failure.malformedPayload }
        return try JSONDecoder().decode([String].self, from: payload)
    }

    static func fragmentsFromDNS(_ data: Data) throws -> [String] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let answers = object["Answer"] as? [[String: Any]] else { throw Failure.malformedPayload }
        // compactMap would silently acknowledge a response with missing fragment data.
        return try answers.map { answer in
            guard let value = answer["data"] as? String else { throw Failure.malformedPayload }
            return value
        }
    }

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            // A captive portal must not receive the request's payload or cookies.
            completionHandler(nil)
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }()

    static func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        try Task.checkCancellation()
        guard request.url?.scheme?.lowercased() == "https", request.url?.user == nil,
              request.url?.password == nil else { throw Failure.insecureURL }
        let result = try await session.data(for: request)
        try validate(result.1)
        return result
    }
}
