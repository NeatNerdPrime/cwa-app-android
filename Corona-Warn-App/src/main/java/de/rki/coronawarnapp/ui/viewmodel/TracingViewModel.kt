package de.rki.coronawarnapp.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rki.coronawarnapp.CoronaWarnApplication
import de.rki.coronawarnapp.exception.ExceptionCategory.INTERNAL
import de.rki.coronawarnapp.exception.TransactionException
import de.rki.coronawarnapp.exception.reporting.report
import de.rki.coronawarnapp.storage.LocalData
import de.rki.coronawarnapp.storage.RiskLevelRepository
import de.rki.coronawarnapp.storage.TracingRepository
import de.rki.coronawarnapp.timer.TimerHelper
import de.rki.coronawarnapp.transaction.RetrieveDiagnosisKeysTransaction
import de.rki.coronawarnapp.transaction.RiskLevelTransaction
import de.rki.coronawarnapp.util.ConnectivityHelper
import de.rki.coronawarnapp.worker.BackgroundConstants.DIAGNOSIS_TEST_RESULT_RETRIEVAL_PER_DAY
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant
import timber.log.Timber
import java.util.Date

/**
 * Provides all the relevant data for tracing relevant topics and settings.
 * The public variables consume MutableLiveDate from two different repositories.
 * This variables are consumed in different views all over the application via bindings, e.g. "@{tracingViewModel.isTracingEnabled}"
 * Most of the values are used in formatters due to the different states the application can have.
 *
 * @see TracingRepository
 * @see RiskLevelRepository
 */
class TracingViewModel : ViewModel() {

    companion object {
        val TAG: String? = TracingViewModel::class.simpleName
    }

    // Values from RiskLevelRepository
    val riskLevel: LiveData<Int> = RiskLevelRepository.riskLevelScore
    val riskLevelScoreLastSuccessfulCalculated =
        RiskLevelRepository.riskLevelScoreLastSuccessfulCalculated

    // Values from ExposureSummaryRepository
    val daysSinceLastExposure: LiveData<Int?> = RiskLevelRepository.daysSinceLastExposure
    val matchedKeyCount: LiveData<Int?> = RiskLevelRepository.matchedKeyCount

    // Values from TracingRepository
    val lastTimeDiagnosisKeysFetched: LiveData<Date> =
        TracingRepository.lastTimeDiagnosisKeysFetched
    val isTracingEnabled: LiveData<Boolean?> = TracingRepository.isTracingEnabled
    val activeTracingDaysInRetentionPeriod = TracingRepository.activeTracingDaysInRetentionPeriod
    var isRefreshing: LiveData<Boolean> = TracingRepository.isRefreshing

    /**
     * Launches the RetrieveDiagnosisKeysTransaction and RiskLevelTransaction in the viewModel scope
     *
     * @see RiskLevelTransaction
     * @see RiskLevelRepository
     */
    fun refreshRiskLevel() {
        viewModelScope.launch {
            try {

                // get the current date and the date the diagnosis keys were fetched the last time
                val currentDate = DateTime(Instant.now(), DateTimeZone.UTC)
                val lastFetch = DateTime(
                    LocalData.lastTimeDiagnosisKeysFromServerFetch(),
                    DateTimeZone.UTC
                )

                // check if the keys were not already retrieved
                val keysWereNotRetrieved =
                    (LocalData.lastTimeDiagnosisKeysFromServerFetch() == null ||
                            lastFetch.isBefore(
                                currentDate.minusHours(
                                    DIAGNOSIS_TEST_RESULT_RETRIEVAL_PER_DAY
                                )
                            )
                            )

                // check if the network is enabled to make the server fetch
                val isNetworkEnabled =
                    ConnectivityHelper.isNetworkEnabled(CoronaWarnApplication.getAppContext())

                // only fetch the diagnosis keys if background jobs are enabled, so that in manual
                // model the keys are only fetched on button press of the user
                val isBackgroundJobEnabled =
                    ConnectivityHelper.autoModeEnabled(CoronaWarnApplication.getAppContext())

                Timber.v("Keys were not retrieved $keysWereNotRetrieved")
                Timber.v("Network is enabled $isNetworkEnabled")
                Timber.v("Background jobs are enabled $isBackgroundJobEnabled")

                if (keysWereNotRetrieved && isNetworkEnabled && isBackgroundJobEnabled) {
                    TracingRepository.isRefreshing.value = true

                    // start the fetching and submitting of the diagnosis keys
                    RetrieveDiagnosisKeysTransaction.start()
                    refreshLastTimeDiagnosisKeysFetchedDate()
                    TimerHelper.checkManualKeyRetrievalTimer()
                }
            } catch (e: TransactionException) {
                //do nothing, we don't want to intercept various network error
            } catch (e: Exception) {
                e.report(INTERNAL)
            }

            // refresh the risk level
            try {
                RiskLevelTransaction.start()
            } catch (e: TransactionException) {
                //do nothing, we don't want to intercept various network error
            } catch (e: Exception) {
                e.report(INTERNAL)
            }

            TracingRepository.isRefreshing.value = false
        }
    }

    /**
     * Refreshes the time when the diagnosis key was fetched the last time
     *
     * @see TracingRepository
     */
    fun refreshLastTimeDiagnosisKeysFetchedDate() {
        TracingRepository.refreshLastTimeDiagnosisKeysFetchedDate()
    }

    /**
     * Refreshes the diagnosis keys
     *
     * @see TracingRepository
     */
    fun refreshDiagnosisKeys() {
        this.viewModelScope.launch {
            TracingRepository.refreshDiagnosisKeys()
            TimerHelper.startManualKeyRetrievalTimer()
        }
    }

    /**
     * Refreshes is tracing enabled
     *
     * @see TracingRepository
     */
    fun refreshIsTracingEnabled() {
        viewModelScope.launch {
            TracingRepository.refreshIsTracingEnabled()
        }
    }

    /**
     * Exposure summary
     * Refresh the following variables in TracingRepository
     * - daysSinceLastExposure
     * - matchedKeysCount
     *
     * @see TracingRepository
     */
    fun refreshExposureSummary() {
        viewModelScope.launch {
            RiskLevelRepository.refreshLastExposureDate()
            RiskLevelRepository.refreshMatchedKeyCount()
        }
    }

    /**
     * Refresh the activeTracingDaysInRetentionPeriod in the viewModel scope
     *
     * @see TracingRepository
     */
    fun refreshActiveTracingDaysInRetentionPeriod() {
        viewModelScope.launch {
            TracingRepository.refreshActiveTracingDaysInRetentionPeriod()
        }
    }

    fun refreshLastSuccessfullyCalculatedScore() {
        RiskLevelRepository.refreshLastSuccessfullyCalculatedScore()
    }
}
