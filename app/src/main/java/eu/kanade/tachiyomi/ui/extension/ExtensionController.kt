package eu.kanade.tachiyomi.ui.extension

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.databinding.ExtensionControllerBinding
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.appcompat.queryTextChanges
import reactivecircus.flowbinding.swiperefreshlayout.refreshes
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Controller to manage the catalogues available in the app.
 */
open class ExtensionController : NucleusController<ExtensionPresenter>(),
        ExtensionAdapter.OnButtonClickListener,
        FlexibleAdapter.OnItemClickListener,
        FlexibleAdapter.OnItemLongClickListener,
        ExtensionTrustDialog.Listener {

    private val preferences: PreferencesHelper = Injekt.get()

    /**
     * Adapter containing the list of manga from the catalogue.
     */
    private var adapter: FlexibleAdapter<IFlexible<*>>? = null

    private var extensions: List<ExtensionItem> = emptyList()

    private var query = ""

    private val uiScope = CoroutineScope(Dispatchers.Main)

    private lateinit var binding: ExtensionControllerBinding

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return applicationContext?.getString(R.string.label_extensions)
    }

    override fun createPresenter(): ExtensionPresenter {
        return ExtensionPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = ExtensionControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        binding.extSwipeRefresh.isRefreshing = true
        binding.extSwipeRefresh.refreshes()
            .onEach { presenter.findAvailableExtensions() }
            .launchIn(uiScope)

        // Initialize adapter, scroll listener and recycler views
        adapter = ExtensionAdapter(this)
        // Create recycler and set adapter.
        binding.extRecycler.layoutManager = LinearLayoutManager(view.context)
        binding.extRecycler.adapter = adapter
        binding.extRecycler.addItemDecoration(ExtensionDividerItemDecoration(view.context))
        adapter?.fastScroller = binding.fastScroller
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> expandActionViewFromInteraction = true
            R.id.action_settings -> {
                router.pushController((RouterTransaction.with(ExtensionFilterController()))
                        .popChangeHandler(SettingsExtensionsFadeChangeHandler())
                        .pushChangeHandler(FadeChangeHandler()))
            }
            R.id.action_auto_check -> {
                item.isChecked = !item.isChecked
                preferences.automaticExtUpdates().set(item.isChecked)
                ExtensionUpdateJob.setupTask(activity!!, item.isChecked)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (!type.isPush && handler is SettingsExtensionsFadeChangeHandler) {
            presenter.findAvailableExtensions()
        }
    }

    override fun onButtonClick(position: Int) {
        val extension = (adapter?.getItem(position) as? ExtensionItem)?.extension ?: return
        when (extension) {
            is Extension.Installed -> {
                if (!extension.hasUpdate) {
                    openDetails(extension)
                } else {
                    presenter.updateExtension(extension)
                }
            }
            is Extension.Available -> {
                presenter.installExtension(extension)
            }
            is Extension.Untrusted -> {
                openTrustDialog(extension)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.extension_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE

        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }

        searchView.queryTextChanges()
            .filter { router.backstack.lastOrNull()?.controller() == this }
            .onEach {
                query = it.toString()
                drawExtensions()
            }
            .launchIn(uiScope)

        // Fixes problem with the overflow icon showing up in lieu of search
        searchItem.fixExpand(onExpand = { invalidateMenuOnExpand() })

        menu.findItem(R.id.action_auto_check).isChecked = preferences.automaticExtUpdates().getOrDefault()
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        val extension = (adapter?.getItem(position) as? ExtensionItem)?.extension ?: return false
        if (extension is Extension.Installed) {
            openDetails(extension)
        } else if (extension is Extension.Untrusted) {
            openTrustDialog(extension)
        }

        return false
    }

    override fun onItemLongClick(position: Int) {
        val extension = (adapter?.getItem(position) as? ExtensionItem)?.extension ?: return
        if (extension is Extension.Installed || extension is Extension.Untrusted) {
            uninstallExtension(extension.pkgName)
        }
    }

    private fun openDetails(extension: Extension.Installed) {
        val controller = ExtensionDetailsController(extension.pkgName)
        router.pushController(controller.withFadeTransaction())
    }

    private fun openTrustDialog(extension: Extension.Untrusted) {
        ExtensionTrustDialog(this, extension.signatureHash, extension.pkgName)
                .showDialog(router)
    }

    fun setExtensions(extensions: List<ExtensionItem>) {
        binding.extSwipeRefresh.isRefreshing = false
        this.extensions = extensions
        drawExtensions()
    }

    fun drawExtensions() {
        if (!query.isBlank()) {
            adapter?.updateDataSet(
                    extensions.filter {
                        it.extension.name.contains(query, ignoreCase = true)
                    })
        } else {
            adapter?.updateDataSet(extensions)
        }
    }

    fun downloadUpdate(item: ExtensionItem) {
        adapter?.updateItem(item, item.installStep)
    }

    override fun trustSignature(signatureHash: String) {
        presenter.trustSignature(signatureHash)
    }

    override fun uninstallExtension(pkgName: String) {
        presenter.uninstallExtension(pkgName)
    }

    class SettingsExtensionsFadeChangeHandler : FadeChangeHandler()
}
