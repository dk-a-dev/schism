package ai.schism.split

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * On-demand WorkManager initialization: the default initializer is removed in the manifest and
 * WorkManager is configured here with Hilt's [HiltWorkerFactory] so `@HiltWorker` workers
 * (SMS ingest/scan) can have their dependencies injected.
 */
@HiltAndroidApp
class SchismApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
