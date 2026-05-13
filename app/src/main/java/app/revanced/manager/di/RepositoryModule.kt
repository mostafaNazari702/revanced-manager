package app.revanced.manager.di

import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.domain.repository.*
import app.revanced.manager.domain.worker.WorkerRepository
import app.revanced.manager.network.api.ApiResponseCache
import app.revanced.manager.network.api.EndpointState
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val repositoryModule = module {
    single { ApiResponseCache(androidApplication()) }
    singleOf(::EndpointState)
    singleOf(::ReVancedRepository)
    singleOf(::ManagerUpdateRepository)
    singleOf(::AnnouncementRepository)
    singleOf(::Filesystem) {
        createdAtStart()
    }
    singleOf(::NetworkInfo)
    singleOf(::PatchSelectionRepository)
    singleOf(::PatchOptionsRepository)
    singleOf(::PatchBundleRepository) {
        // It is best to load patch bundles ASAP
        createdAtStart()
    }
    singleOf(::DownloaderRepository)
    singleOf(::WorkerRepository)
    singleOf(::DownloadedAppRepository)
    singleOf(::InstalledAppRepository)
    singleOf(::ChangelogsRepository)
}