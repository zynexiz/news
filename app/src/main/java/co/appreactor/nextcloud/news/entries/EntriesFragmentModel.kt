package co.appreactor.nextcloud.news.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.appreactor.nextcloud.news.common.*
import co.appreactor.nextcloud.news.db.Entry
import co.appreactor.nextcloud.news.feeds.FeedsRepository
import co.appreactor.nextcloud.news.db.Feed
import co.appreactor.nextcloud.news.entriesimages.EntriesImagesRepository
import co.appreactor.nextcloud.news.podcasts.EntriesAudioRepository
import co.appreactor.nextcloud.news.podcasts.isPodcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import timber.log.Timber
import java.text.DateFormat
import java.util.*
import kotlin.system.measureTimeMillis

class EntriesFragmentModel(
    private val feedsRepository: FeedsRepository,
    private val entriesRepository: EntriesRepository,
    private val entriesSummariesRepository: EntriesSummariesRepository,
    private val entriesImagesRepository: EntriesImagesRepository,
    private val entriesAudioRepository: EntriesAudioRepository,
    private val newsApiSync: NewsApiSync,
    private val prefs: Preferences,
) : ViewModel() {

    init {
        viewModelScope.launch {
            entriesAudioRepository.deleteCompletedDownloadsWithoutFiles()
            entriesAudioRepository.deletePartialDownloads()
        }

        viewModelScope.launch {
            if (getShowPreviewImages().first()) {
                entriesImagesRepository.syncPreviews()
            }
        }
    }

    suspend fun getEntries(): Flow<List<EntriesAdapterItem>> {
        val start = System.currentTimeMillis()
        var reported = false

        return combine(
            feedsRepository.getAll(),
            entriesRepository.getAllWithoutSummary(),
            getShowReadEntries(),
            getShowPreviewImages(),
            getCropPreviewImages(),
        ) { feeds, entries, showViewedEntries, showPreviewImages, cropPreviewImages ->
            Timber.d("Got results in ${System.currentTimeMillis() - start} ms")

            val result = entries.filter {
                showViewedEntries || !it.viewed
            }.map {
                val feed = feeds.singleOrNull { feed -> feed.id == it.feedId }
                it.toRow(feed, showPreviewImages, cropPreviewImages)
            }

            if (!reported) {
                reported = true
                Timber.d("Prepared results in ${System.currentTimeMillis() - start} ms")
            }

            result
        }
    }

    suspend fun performInitialSyncIfNoData() {
        newsApiSync.performInitialSyncIfNotDone()
    }

    suspend fun performFullSync() {
        newsApiSync.sync()
    }

    suspend fun isInitialSyncCompleted() = prefs.initialSyncCompleted().first()

    suspend fun downloadPodcast(id: String) {
        entriesAudioRepository.downloadPodcast(id)
    }

    suspend fun getEntry(id: String) = entriesRepository.get(id).first()

    suspend fun markAsViewed(entryId: String) {
        entriesRepository.setViewed(entryId, true)
        newsApiSync.syncEntriesFlags()
    }

    suspend fun markAsViewedAndBookmarked(entryId: String) = withContext(Dispatchers.IO) {
        entriesRepository.setViewed(entryId, true)
        entriesRepository.setBookmarked(entryId, true)
        newsApiSync.syncEntriesFlags()
    }

    private suspend fun getShowReadEntries() = prefs.showReadEntries()

    private suspend fun getShowPreviewImages() = prefs.showPreviewImages()

    private suspend fun getCropPreviewImages() = prefs.cropPreviewImages()

    private suspend fun Entry.toRow(
        feed: Feed?,
        showFeedImages: Boolean,
        cropFeedImages: Boolean,
    ): EntriesAdapterItem {
        return EntriesAdapterItem(
            id = id,
            title = title,
            subtitle = lazy {
                val publishedDateTime = LocalDateTime.parse(published)
                val publishedDateString = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(publishedDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()))
                (feed?.title ?: "Unknown feed") + " · " + publishedDateString
            },
            viewed = viewed,
            podcast = isPodcast(),
            podcastDownloadPercent = flow {
                entriesAudioRepository.getDownloadProgress(this@toRow.id).collect {
                    emit(it)
                }
            },
            image = flow {
                entriesImagesRepository.getPreviewImage(this@toRow).collect {
                    emit(it)
                }
            },
            cachedImage = lazy {
                runBlocking {
                    entriesImagesRepository.getPreviewImage(this@toRow).first()
                }

            },
            showImage = showFeedImages,
            cropImage = cropFeedImages,
            summary = flow { emit(entriesSummariesRepository.getSummary(this@toRow.id)) },
            cachedSummary = entriesSummariesRepository.getCachedSummary(this.id),
        )
    }

    @Suppress("unused")
    private fun runBenchmarks() {
        viewModelScope.launch {
            val getAllEntriesTime = measureTimeMillis { entriesRepository.getAll().first() }
            val getAllEntriesWithoutSummaryTime = measureTimeMillis { entriesRepository.getAllWithoutSummary().first() }

            Timber.d("Get all entries: $getAllEntriesTime")
            Timber.d("Get all entries (excl summary): $getAllEntriesWithoutSummaryTime")

            val getAllFeedsTime = measureTimeMillis { feedsRepository.getAll().first() }
            Timber.d("Get all feeds: $getAllFeedsTime")
        }
    }
}