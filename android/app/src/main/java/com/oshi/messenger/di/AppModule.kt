package com.oshi.messenger.di

import android.content.Context
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.local.BotDao
import com.oshi.messenger.data.local.GroupDao
import com.oshi.messenger.service.NLPManager
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.ipfs.IPFSCleanupService
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.network.web.WebSessionManager
import com.oshi.messenger.service.*
import com.oshi.messenger.util.CalendarExportHelper
import com.oshi.messenger.service.MapStorageManager
import com.oshi.messenger.service.MapPOIDatabase
import com.oshi.messenger.service.OfflineMapManager
import com.oshi.messenger.service.OfflineRoutingManager
import com.oshi.messenger.service.OfflineStreetDatabase
import com.oshi.messenger.service.map.OfflineRouter
import com.oshi.messenger.service.map.RoadGraphImporter
import com.oshi.messenger.service.map.OfflineRoutingManager as MapOfflineRoutingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

// Note: AudioRecorderManager and AudioPlayerManager are provided via @Inject constructor

/** Marks the process-lifetime [CoroutineScope] provided by [AppModule]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }

    @Provides
    @Singleton
    fun provideDoubleRatchet(cryptoManager: CryptoManager): DoubleRatchet {
        return DoubleRatchet(cryptoManager)
    }

    @Provides
    @Singleton
    fun provideVPSClient(
        cryptoManager: CryptoManager,
        v2Signer: com.oshi.messenger.network.v2.V2Signer,
        @ApplicationContext context: Context
    ): VPSClient {
        return VPSClient(cryptoManager, v2Signer, context)
    }

    @Provides
    @Singleton
    fun provideMeshNetworkManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        crossPlatformMesh: CrossPlatformMesh
    ): MeshNetworkManager {
        return MeshNetworkManager(context, cryptoManager, crossPlatformMesh)
    }

    @Provides
    @Singleton
    fun provideCrossPlatformMesh(
        @ApplicationContext context: Context,
        // 🔐 PEER AUTH: CrossPlatformMesh now needs the identity key pair to run the
        // DH proof-of-possession challenge-response (additive/advisory).
        cryptoManager: CryptoManager
    ): CrossPlatformMesh {
        return CrossPlatformMesh(context, cryptoManager)
    }

    @Provides
    @Singleton
    fun provideCallManager(
        @ApplicationContext context: Context,
        vpsClient: VPSClient,
        meshManager: MeshNetworkManager,
        crossPlatformMesh: CrossPlatformMesh,
        cryptoManager: CryptoManager,
        database: AppDatabase
    ): CallManager {
        return CallManager(context, vpsClient, meshManager, crossPlatformMesh, cryptoManager, database)
    }

    @Provides
    @Singleton
    fun provideWebSessionManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        v2Signer: V2Signer
    ): WebSessionManager {
        return WebSessionManager(context, cryptoManager, v2Signer)
    }

    @Provides
    @Singleton
    fun provideIPFSCleanupService(
        @ApplicationContext context: Context
    ): IPFSCleanupService {
        return IPFSCleanupService(context)
    }

    // =========================================================================
    // NEW MANAGERS
    // =========================================================================

    @Provides
    @Singleton
    fun provideGroupManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        doubleRatchet: DoubleRatchet,
        vpsClient: VPSClient,
        crossPlatformMesh: CrossPlatformMesh,
        // __GROUP_E2E_V2_2026_09_23__ every group byte goes out over v2 (docs/GROUP_E2E_V2_SPEC.md).
        // Lazy: the router must not be built before Hilt has the whole graph.
        v2MessageRouter: dagger.Lazy<com.oshi.messenger.network.v2.V2MessageRouter>,
        // __INTEROP_2026_11_21__ the public-link registry (GROUP_INFO_SETTINGS_SPEC §4); it was
        // never passed, so links never resolved and Public could never be set.
        groupHandleClient: dagger.Lazy<com.oshi.messenger.network.v2.V2GroupHandleClient>
    ): GroupManager {
        // Named arguments throughout: this call was positional, so adding one
        // parameter in the middle silently bound `vpsClient` to the new slot and
        // only failed because the types differed. Names make the next addition
        // a compile error at the definition, not a lucky type clash here.
        return GroupManager(
            context = context,
            cryptoManager = cryptoManager,
            doubleRatchet = doubleRatchet,
            vpsClient = vpsClient,
            crossPlatformMesh = crossPlatformMesh,
            v2Router = v2MessageRouter,
            handleClientLazy = groupHandleClient
        )
    }

    @Provides
    @Singleton
    fun provideMessageReactionManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        vpsClient: VPSClient,
        database: AppDatabase
    ): MessageReactionManager {
        return MessageReactionManager(context, cryptoManager, vpsClient, database)
    }

    @Provides
    @Singleton
    fun provideTypingIndicatorManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        vpsClient: VPSClient,
        // __CONTROL_OVER_V2_2026_10_28__ typing now prefers the V2 lane; see
        // TypingIndicatorManager's constructor for why it had to.
        v2MessageRouter: com.oshi.messenger.network.v2.V2MessageRouter
    ): TypingIndicatorManager {
        return TypingIndicatorManager(context, cryptoManager, vpsClient, v2MessageRouter)
    }

    @Provides
    @Singleton
    fun provideScheduledCallManager(
        @ApplicationContext context: Context,
        vpsClient: VPSClient,
        database: AppDatabase
    ): ScheduledCallManager {
        return ScheduledCallManager(context, vpsClient, database)
    }

    @Provides
    @Singleton
    fun provideMessageActionsManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        vpsClient: VPSClient
    ): MessageActionsManager {
        return MessageActionsManager(context, cryptoManager, vpsClient)
    }

    @Provides
    @Singleton
    fun provideSafetyNumberManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        database: AppDatabase
    ): SafetyNumberManager {
        // `database` is new: marking a contact verified now writes the contact row as
        // well as the fingerprint in prefs, so the badge the UI reads actually flips.
        return SafetyNumberManager(context, cryptoManager, database)
    }

    @Provides
    @Singleton
    fun provideKeyRotationManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        vpsClient: VPSClient
    ): KeyRotationManager {
        return KeyRotationManager(context, cryptoManager, vpsClient)
    }

    @Provides
    @Singleton
    fun provideScreenshotProtectionManager(
        @ApplicationContext context: Context
    ): ScreenshotProtectionManager {
        return ScreenshotProtectionManager(context)
    }

    @Provides
    @Singleton
    fun provideMeshBoosterManager(
        @ApplicationContext context: Context,
        meshNetworkManager: MeshNetworkManager,
        crossPlatformMesh: CrossPlatformMesh
    ): MeshBoosterManager {
        return MeshBoosterManager(context, meshNetworkManager, crossPlatformMesh)
    }

    @Provides
    @Singleton
    fun provideMediaCompressionManager(
        @ApplicationContext context: Context
    ): MediaCompressionManager {
        return MediaCompressionManager(context)
    }

    @Provides
    @Singleton
    fun provideMultiDeviceSyncManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager,
        vpsClient: VPSClient
    ): MultiDeviceSyncManager {
        return MultiDeviceSyncManager(context, cryptoManager, vpsClient)
    }

    @Provides
    @Singleton
    fun provideCalendarExportHelper(
        @ApplicationContext context: Context
    ): CalendarExportHelper {
        return CalendarExportHelper(context)
    }

    // =========================================================================
    // AUDIO MANAGERS
    // =========================================================================

    @Provides
    @Singleton
    fun provideAudioRecorderManager(
        @ApplicationContext context: Context
    ): AudioRecorderManager {
        return AudioRecorderManager(context)
    }

    @Provides
    @Singleton
    fun provideAudioPlayerManager(
        @ApplicationContext context: Context
    ): AudioPlayerManager {
        return AudioPlayerManager(context)
    }

    // =========================================================================
    // ENHANCED CALL SYSTEM (iOS Compatible)
    // =========================================================================

    @Provides
    @Singleton
    fun provideEnhancedCallManager(
        @ApplicationContext context: Context,
        vpsClient: VPSClient,
        meshManager: MeshNetworkManager,
        crossPlatformMesh: CrossPlatformMesh,
        cryptoManager: CryptoManager,
        database: AppDatabase,
        videoCallManager: VideoCallManager
    ): EnhancedCallManager {
        return EnhancedCallManager(context, vpsClient, meshManager, crossPlatformMesh, cryptoManager, database, videoCallManager)
    }

    @Provides
    @Singleton
    fun provideVideoCallManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager
    ): VideoCallManager {
        return VideoCallManager(context, cryptoManager)
    }

    @Provides
    @Singleton
    fun provideBotDao(database: AppDatabase): BotDao {
        return database.botDao()
    }

    /** __BOTS_PHASE1_2026_11_24__ BotFather / "Add a bot" routes; tests use a fake. */
    @Provides
    @Singleton
    fun provideBotDirectoryApi(
        impl: com.oshi.messenger.network.bot.HttpBotDirectoryApi
    ): com.oshi.messenger.network.bot.BotDirectoryApi = impl

    @Provides
    @Singleton
    fun provideGroupDao(database: AppDatabase): GroupDao {
        return database.groupDao()
    }

    @Provides
    @Singleton
    fun provideProfileLinksManager(
        @ApplicationContext context: Context
    ): ProfileLinksManager {
        return ProfileLinksManager(context)
    }

    @Provides
    @Singleton
    fun provideNLPManager(
        @ApplicationContext context: Context,
        localLLMManager: LocalLLMManager
    ): NLPManager {
        return NLPManager(context, localLLMManager)
    }

    @Provides
    @Singleton
    fun provideLocalLLMManager(
        @ApplicationContext context: Context
    ): LocalLLMManager {
        return LocalLLMManager(context)
    }

    /**
     * One live-share state per PROCESS, matching iOS `LocationSharingManager.shared`
     * (`OSHI/LocationSharingManager.swift:20`).
     *
     * `ChatScreen` and `GroupChatScreen` used to build their own with
     * `remember { LocationSharingManager(context) }`. `@Singleton` on the class is
     * inert without a binding, so leaving the conversation dropped the only object
     * that knew a share was running: no banner, no Stop button, and the
     * `LocationCallback` kept GPS at `PRIORITY_HIGH_ACCURACY` until the 8-hour
     * expiry.
     */
    @Provides
    @Singleton
    fun provideLocationSharingManager(
        @ApplicationContext context: Context
    ): LocationSharingManager {
        return LocationSharingManager(context)
    }

    /**
     * Process-lifetime scope for work that must outlive the screen that started it.
     *
     * A live-location update fired 40 minutes into a share must still be delivered
     * after the user has left the chat and its `ViewModel` has been cleared —
     * `viewModelScope` is cancelled by then and the send is silently dropped.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideBotManager(
        @ApplicationContext context: Context,
        database: AppDatabase,
        vpsClient: VPSClient,
        cryptoManager: CryptoManager,
        groupManager: GroupManager,
        botGroupStore: com.oshi.messenger.service.BotGroupStore
    ): BotManager {
        return BotManager(context, database, vpsClient, cryptoManager, groupManager, botGroupStore)
    }

    // =========================================================================
    // OFFLINE MAPS
    // =========================================================================

    @Provides
    @Singleton
    fun provideMapStorageManager(
        @ApplicationContext context: Context
    ): MapStorageManager {
        return MapStorageManager(context)
    }

    @Provides
    @Singleton
    fun provideMapPOIDatabase(
        @ApplicationContext context: Context,
        mapStorageManager: MapStorageManager
    ): MapPOIDatabase {
        return MapPOIDatabase(context, mapStorageManager)
    }

    @Provides
    @Singleton
    fun provideOfflineMapManager(
        @ApplicationContext context: Context,
        mapStorageManager: MapStorageManager,
        mapPOIDatabase: MapPOIDatabase,
        offlineRoutingManager: OfflineRoutingManager,
        offlineStreetDatabase: OfflineStreetDatabase
    ): OfflineMapManager {
        return OfflineMapManager(context, mapStorageManager, mapPOIDatabase, offlineRoutingManager, offlineStreetDatabase)
    }

    @Provides
    @Singleton
    fun provideOfflineRouter(
        @ApplicationContext context: Context
    ): OfflineRouter = OfflineRouter(context)

    @Provides
    @Singleton
    fun provideRoadGraphImporter(
        @ApplicationContext context: Context
    ): RoadGraphImporter = RoadGraphImporter(context)

    @Provides
    @Singleton
    fun provideMapOfflineRoutingManager(
        @ApplicationContext context: Context,
        importer: RoadGraphImporter,
        router: OfflineRouter
    ): MapOfflineRoutingManager = MapOfflineRoutingManager(context, importer, router)

    @Provides
    @Singleton
    fun provideOfflineRoutingManager(
        @ApplicationContext context: Context,
        mapStorageManager: MapStorageManager,
        mapRoutingManager: MapOfflineRoutingManager,
        router: OfflineRouter
    ): OfflineRoutingManager {
        return OfflineRoutingManager(context, mapStorageManager, mapRoutingManager, router)
    }

    @Provides
    @Singleton
    fun provideOfflineStreetDatabase(
        @ApplicationContext context: Context,
        mapStorageManager: MapStorageManager
    ): OfflineStreetDatabase {
        return OfflineStreetDatabase(context, mapStorageManager)
    }
}
