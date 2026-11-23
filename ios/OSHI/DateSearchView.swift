//
//  DateSearchView.swift
//  OSHI
//
//  Calendar-based message search component
//  ✅ FULLY LOCALIZED
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Date Search View

struct DateSearchView: View {
    @Binding var isPresented: Bool
    @Binding var selectedDate: Date?
    @Binding var dateRangeStart: Date?
    @Binding var dateRangeEnd: Date?
    
    var onDateSelected: ((Date) -> Void)?
    var onRangeSelected: ((Date, Date) -> Void)?
    var onClearFilter: (() -> Void)?
    
    @State private var tempDate: Date = Date()
    @State private var tempStartDate: Date = Calendar.current.date(byAdding: .day, value: -7, to: Date()) ?? Date()
    @State private var tempEndDate: Date = Date()
    @State private var searchMode: DateSearchMode = .singleDate
    
    enum DateSearchMode: String, CaseIterable {
        case singleDate
        case dateRange
        case quickSelect
        
        var title: String {
            switch self {
            case .singleDate: return "search.select_date".localized
            case .dateRange: return "search.date_range".localized
            case .quickSelect: return "search.calendar".localized
            }
        }
    }
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Mode selector
                Picker("", selection: $searchMode) {
                    ForEach(DateSearchMode.allCases, id: \.self) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .padding()
                
                Divider()
                
                ScrollView {
                    VStack(spacing: 20) {
                        switch searchMode {
                        case .singleDate:
                            singleDateView
                        case .dateRange:
                            dateRangeView
                        case .quickSelect:
                            quickSelectView
                        }
                    }
                    .padding()
                }
                
                Divider()

                // Action buttons
                HStack(spacing: 16) {
                    if selectedDate != nil || dateRangeStart != nil {
                        Button {
                            clearFilter()
                        } label: {
                            Text("search.clear_filter".localized)
                                .font(.headline)
                                .foregroundColor(.red)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .padding()
                                .background(Color.red.opacity(0.1))
                                .cornerRadius(12)
                        }
                        .transition(.opacity.combined(with: .move(edge: .leading)))
                    }

                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        #endif
                        applyFilter()
                    } label: {
                        Text("search.apply_filter".localized)
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding()
                            .background(
                                LinearGradient(
                                    colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .cornerRadius(12)
                    }
                }
                .padding()
                .animation(.easeInOut(duration: 0.2), value: selectedDate != nil || dateRangeStart != nil)
            }
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
            .navigationTitle("search.by_date".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) {
                        isPresented = false
                    }
                }
            }
        }
    }
    
    // MARK: - Single Date View
    
    private var singleDateView: some View {
        VStack(spacing: 16) {
            DatePicker(
                "search.select_date".localized,
                selection: $tempDate,
                in: ...Date(),
                displayedComponents: [.date]
            )
            .datePickerStyle(.graphical)
            .accentColor(appBrandColor)
            
            // Selected date preview
            HStack {
                Image(systemName: "calendar")
                    .foregroundColor(appBrandColor)
                    .accessibilityHidden(true)
                Text(formatDate(tempDate))
                    .font(.headline)
            }
            .padding()
            .frame(maxWidth: .infinity)
            .background(Color(.systemGray6))
            .cornerRadius(12)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(formatDate(tempDate))
        }
    }
    
    // MARK: - Date Range View
    
    private var dateRangeView: some View {
        VStack(spacing: 20) {
            // Start date
            VStack(alignment: .leading, spacing: 8) {
                Text("search.from_date".localized)
                    .font(.headline)
                    .foregroundColor(.secondary)
                
                DatePicker(
                    "",
                    selection: $tempStartDate,
                    in: ...tempEndDate,
                    displayedComponents: [.date]
                )
                .datePickerStyle(.compact)
                .labelsHidden()
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
            
            // Arrow
            Image(systemName: "arrow.down")
                .font(.title2)
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
            
            // End date
            VStack(alignment: .leading, spacing: 8) {
                Text("search.to_date".localized)
                    .font(.headline)
                    .foregroundColor(.secondary)
                
                DatePicker(
                    "",
                    selection: $tempEndDate,
                    in: tempStartDate...Date(),
                    displayedComponents: [.date]
                )
                .datePickerStyle(.compact)
                .labelsHidden()
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
            
            // Range preview
            HStack {
                VStack(alignment: .leading) {
                    Text(formatDate(tempStartDate))
                        .font(.subheadline)
                    Text("→ " + formatDate(tempEndDate))
                        .font(.subheadline)
                }
                Spacer()
                Text(daysBetween(tempStartDate, tempEndDate))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(appBrandColor.opacity(0.1))
            .cornerRadius(12)
            .accessibilityElement(children: .combine)
        }
    }
    
    // MARK: - Quick Select View
    
    private var quickSelectView: some View {
        VStack(spacing: 12) {
            QuickSelectButton(
                title: "search.today".localized,
                icon: "sun.max.fill",
                color: .orange
            ) {
                tempDate = Date()
                searchMode = .singleDate
            }
            
            QuickSelectButton(
                title: "search.this_week".localized,
                icon: "calendar.badge.clock",
                color: appBrandColor
            ) {
                let calendar = Calendar.current
                tempStartDate = calendar.date(byAdding: .day, value: -7, to: Date()) ?? Date()
                tempEndDate = Date()
                searchMode = .dateRange
            }
            
            QuickSelectButton(
                title: "search.this_month".localized,
                icon: "calendar",
                color: .purple
            ) {
                let calendar = Calendar.current
                tempStartDate = calendar.date(byAdding: .month, value: -1, to: Date()) ?? Date()
                tempEndDate = Date()
                searchMode = .dateRange
            }
            
            QuickSelectButton(
                title: "search.custom_range".localized,
                icon: "slider.horizontal.3",
                color: .green
            ) {
                searchMode = .dateRange
            }
        }
    }
    
    // MARK: - Helpers
    
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .long
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }
    
    private func daysBetween(_ start: Date, _ end: Date) -> String {
        let days = Calendar.current.dateComponents([.day], from: start, to: end).day ?? 0
        if days == 0 {
            return "search.today".localized
        } else if days == 1 {
            return "1 day"
        } else {
            return "\(days) days"
        }
    }
    
    private func applyFilter() {
        switch searchMode {
        case .singleDate:
            selectedDate = tempDate
            dateRangeStart = nil
            dateRangeEnd = nil
            onDateSelected?(tempDate)
        case .dateRange, .quickSelect:
            selectedDate = nil
            dateRangeStart = tempStartDate
            dateRangeEnd = tempEndDate
            onRangeSelected?(tempStartDate, tempEndDate)
        }
        isPresented = false
    }
    
    private func clearFilter() {
        selectedDate = nil
        dateRangeStart = nil
        dateRangeEnd = nil
        onClearFilter?()
        isPresented = false
    }
}

// MARK: - Quick Select Button

struct QuickSelectButton: View {
    let title: String
    let icon: String
    let color: Color
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundColor(color)
                    .frame(width: 40)
                    .accessibilityHidden(true)

                Text(title)
                    .font(.headline)
                    .foregroundColor(.primary)

                Spacer()

                Image(systemName: "chevron.right")
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
            }
            .padding()
            .frame(minHeight: 44)
            .background(Color(.systemGray6))
            .cornerRadius(12)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Date Filter Badge

struct DateFilterBadge: View {
    let date: Date?
    let startDate: Date?
    let endDate: Date?
    let onClear: () -> Void
    
    var body: some View {
        if date != nil || startDate != nil {
            HStack(spacing: 8) {
                Image(systemName: "calendar.badge.clock")
                    .foregroundColor(appBrandColor)
                    .accessibilityHidden(true)

                if let date = date {
                    Text(formatShortDate(date))
                        .font(.caption)
                        .fontWeight(.medium)
                } else if let start = startDate, let end = endDate {
                    Text("\(formatShortDate(start)) - \(formatShortDate(end))")
                        .font(.caption)
                        .fontWeight(.medium)
                }

                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.gray)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("search.clear_filter".localized)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(appBrandColor.opacity(0.1))
            .cornerRadius(20)
        }
    }
    
    private func formatShortDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d"
        return formatter.string(from: date)
    }
}

// MARK: - Preview

#Preview {
    DateSearchView(
        isPresented: .constant(true),
        selectedDate: .constant(nil),
        dateRangeStart: .constant(nil),
        dateRangeEnd: .constant(nil)
    )
}
