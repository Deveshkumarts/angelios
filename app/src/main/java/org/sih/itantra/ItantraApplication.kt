package org.sih.itantra

import android.app.Application
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.service.MeshForegroundService

class ItantraApplication : Application() {
    lateinit var communicationRepository: CommunicationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        communicationRepository = CommunicationRepository(this)

        // Claim a foreground-service lifetime for the mesh listener immediately, so BLE/Wi-Fi
        // discovery and SOS reception keep running once the user leaves or swipes the app away.
        try {
            MeshForegroundService.start(this)
        } catch (t: Throwable) {
            android.util.Log.e("ItantraApplication", "Failed to start MeshForegroundService: ${t.message}", t)
        }
    }
}
