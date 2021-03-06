package br.ufs.hiring.nubankchallenge.chargeback

import android.content.Intent
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.util.Log
import android.view.View
import br.ufs.hiring.nubankchallenge.R
import br.ufs.hiring.nubankchallenge.factories.PresentationFactory
import br.ufs.hiring.nubankchallenge.transaction.PurchaseDetailsActivity
import br.ufs.hiring.nubankchallenge.util.action
import br.ufs.hiring.nubankchallenge.util.colorForActionText
import br.ufs.hiring.nubankchallenge.util.screenProvider
import br.ufs.nubankchallenge.core.domain.errors.InfrastructureError
import br.ufs.nubankchallenge.core.domain.errors.NetworkingIssue
import br.ufs.nubankchallenge.core.presentation.chargeback.ChargebackScreenModel
import br.ufs.nubankchallenge.core.presentation.chargeback.CreditcardState
import br.ufs.nubankchallenge.core.presentation.errorstate.ErrorStateView
import br.ufs.nubankchallenge.core.presentation.loading.LoadingView
import br.ufs.nubankchallenge.core.presentation.networking.NetworkingErrorView
import com.afollestad.materialdialogs.MaterialDialog
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Action
import kotlinx.android.synthetic.main.activity_chargeback.*
import kotlinx.android.synthetic.main.view_creditcard_state.*
import kotlinx.android.synthetic.main.view_error_feedback.*

/**
 *
 * Created by @ubiratanfsoares
 *
 */

class ChargebackActivity : AppCompatActivity(),
        LoadingView, NetworkingErrorView, ErrorStateView {

    val screen by screenProvider { PresentationFactory.chargebackScreen() }
    val presenter by lazy { PresentationFactory.behaviorsPresenter(this) }

    private val dialog by lazy {
        MaterialDialog.Builder(this)
                .cancelable(false)
                .customView(R.layout.view_chargeback_submission, true)
                .build()
    }

    private val subscriptions by lazy { CompositeDisposable() }

    private var finished: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chargeback)
        setupViews()
    }

    override fun finish() {
        super.finish()

        val exitAnimation = if (finished) R.anim.slidedown else android.R.anim.fade_out
        overridePendingTransition(
                android.R.anim.fade_in,
                exitAnimation
        )
    }

    override fun onResume() {
        super.onResume()
        retrieveChargeback()
    }

    override fun onDestroy() {
        if (dialog.isShowing) dialog.dismiss()
        subscriptions.clear()
        super.onDestroy()
    }

    override fun showLoading() = Action { loadingChargeback.visibility = View.VISIBLE }

    override fun hideLoading() = Action { loadingChargeback.visibility = View.GONE }

    override fun showErrorState(error: InfrastructureError) = Action {
        showFeedback(error)

        when (error) {
            is InfrastructureError.UndesiredResponse ->
                callToAction(
                        R.string.snacktext_undesired_response,
                        { finish() },
                        R.string.snackaction_done
                )
            is InfrastructureError.RemoteSystemDown ->
                callToAction(
                        R.string.snacktext_servers_down,
                        { forceScreenUpdate() }
                )
        }
    }

    override fun reportNetworkingError(issue: NetworkingIssue) = Action {
        showFeedback(issue)
        callToAction(
                R.string.snacktext_internet_connection,
                { forceScreenUpdate() }
        )
    }

    override fun hideErrorState() = Action { clearErrorFeedback() }

    private fun retrieveChargeback(forceUpdate: Boolean = false) {
        val disposable = screen.chargebackOptions(forceUpdate)
                .compose(presenter)
                .subscribe(
                        { fillChargeback(it as ChargebackScreenModel) },
                        { Log.e("Chargeback", "Error -> $it") },
                        { Log.v("Chargeback", "Loaded with success") }
                )

        subscriptions.add(disposable)
    }

    private fun fillChargeback(model: ChargebackScreenModel) {
        reasonsView.adapter = null
        showChargebackViews()
        chargebackTitleLabel.text = model.screenTitle

        userCommentInput.apply {
            hint = model.commentHint
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                hint = if (hasFocus) "" else model.commentHint
            }
        }

        fillCreditcardInfo(model.creditcardState)
        reasonsView.adapter = ReclaimReasonsAdapter(model.reasons)
    }

    private fun fillCreditcardInfo(creditcardState: CreditcardState) {
        lockpadView.setActualCreditcardState(creditcardState)
    }

    private fun setupViews() {
        reasonsView.layoutManager = LinearLayoutManager(this)
        leaveChargebackButton.setOnClickListener { finish() }
        performChargebackButton.setOnClickListener { requestChargeback() }
    }

    private fun showChargebackViews() {
        View.VISIBLE.let {
            lockpadView.visibility = it
            screenTitle.visibility = it
            chargebackContainer.visibility = it
            actionsContainer.visibility = it
        }
    }

    private fun showFeedback(error: Throwable) {
        feedbackContainer.apply {
            visibility = View.VISIBLE
            setState(error)
        }
    }

    private fun callToAction(
            callToActionText: Int,
            retryAction: (Any) -> Unit,
            retryText: Int = R.string.snackaction_retry) {

        Snackbar.make(window.decorView.rootView, callToActionText, Snackbar.LENGTH_INDEFINITE)
                .action(retryText, retryAction)
                .colorForActionText(R.color.gray_call_to_action)
                .show()
    }

    private fun requestChargeback() {
        val submissionView = dialog.customView.let { it as ChargebackSubmissionView }
        val userComment = userCommentInput.text.toString()
        val reasons = reasonsView.adapter.let { it as ReclaimReasonsAdapter }.reasons

        val disposable = screen
                .sendChargebackReclaim(userComment, reasons)
                .compose(submissionView.presenter)
                .doOnSubscribe {
                    submissionView.configureWith(
                            success = { done() },
                            error = { dialog.dismiss() }
                    )
                    dialog.show()
                }
                .subscribe(
                        {
                            submissionView.displaySuccess()
                            hideAllOtherViews()
                            finished = true
                        },
                        { Log.e("Chargeback", "Error -> $it") }
                )

        subscriptions.add(disposable)
    }

    private fun done() {
        val intent = Intent(this, PurchaseDetailsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun hideAllOtherViews() {
        View.INVISIBLE.let {
            lockpadView.visibility = it
            screenTitle.visibility = it
            chargebackContainer.visibility = it
            actionsContainer.visibility = it
        }
    }

    private fun forceScreenUpdate() {
        releaseSubscriptions()
        clearErrorFeedback()
        retrieveChargeback(forceUpdate = true)
    }

    private fun clearErrorFeedback() {
        feedbackContainer.visibility = View.GONE
        feedbackContainer.reset()
    }

    private fun releaseSubscriptions() {
        subscriptions.clear()
    }
}