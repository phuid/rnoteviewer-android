package com.example.rnoteviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.rnoteviewer.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity(), FontPickerHost {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DocumentViewModel by viewModels()

    private var currentDocument: RnoteDocument? = null
    private var totalPages = 1

    private var searchResults: List<SearchResult> = emptyList()
    private var currentMatchIndex: Int = -1
    private var isRegexEnabled: Boolean = false
    private var isCaseSensitiveEnabled: Boolean = false

    // Handler per nascondere l'indicatore pagina dopo 1.5 s
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideIndicatorRunnable = Runnable {
        binding.pageIndicator.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.pageIndicator.visibility = View.GONE }
            .start()
    }

    // ── Launchers ─────────────────────────────

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadUri(it) } }

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val dialog = supportFragmentManager
            .findFragmentByTag(FontsDialogFragment.TAG) as? FontsDialogFragment
            ?: FontsDialogFragment.newInstance()
        dialog.installFontFromUri(this, uri)
        if (!dialog.isAdded) FontsDialogFragment.newInstance()
            .show(supportFragmentManager, FontsDialogFragment.TAG)
    }

    // ── Lifecycle ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnOpenFile.setOnClickListener { openFileLauncher.launch(arrayOf("*/*")) }
        binding.fabJumpPage.setOnClickListener { showJumpDialog() }
        binding.fabOriginPage.setOnClickListener { binding.rnoteView.returnToOriginPage() }

        setupScrollbar()
        setupScrollListener()
        setupSearchUI()
        observeViewModel()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ── Menu ──────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open        -> { openFileLauncher.launch(arrayOf("*/*")); true }
        R.id.action_origin_page -> { binding.rnoteView.returnToOriginPage(); true }
        R.id.action_search      -> { showSearchUI(); true }
        R.id.action_fonts       -> {
            FontsDialogFragment.newInstance().show(supportFragmentManager, FontsDialogFragment.TAG)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ── FontPickerHost ────────────────────────

    override fun openFontPicker() { fontPickerLauncher.launch(arrayOf("*/*")) }

    // ── Search UI ─────────────────────────────

    private fun setupSearchUI() {
        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnToggleRegex.setOnClickListener {
            isRegexEnabled = !isRegexEnabled
            updateToggleButtonsUI()
            performSearch()
        }

        binding.btnToggleCase.setOnClickListener {
            isCaseSensitiveEnabled = !isCaseSensitiveEnabled
            updateToggleButtonsUI()
            performSearch()
        }

        binding.btnSearchPrev.setOnClickListener {
            if (searchResults.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchResults.lastIndex
                updateMatchSelection()
            }
        }

        binding.btnSearchNext.setOnClickListener {
            if (searchResults.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex < searchResults.lastIndex) currentMatchIndex + 1 else 0
                updateMatchSelection()
            }
        }

        binding.btnCloseSearch.setOnClickListener {
            hideSearchUI()
        }

        updateToggleButtonsUI()
    }

    private fun updateToggleButtonsUI() {
        val primaryColor = binding.toolbar.context.theme.let { theme ->
            val typedValue = TypedValue()
            if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
                typedValue.data
            } else 0xFF6200EE.toInt()
        }
        val inactiveColor = 0xFF888888.toInt()

        binding.btnToggleRegex.setTextColor(if (isRegexEnabled) primaryColor else inactiveColor)
        binding.btnToggleRegex.setStrokeColorResource(
            if (isRegexEnabled) android.R.color.holo_blue_dark else android.R.color.darker_gray
        )

        binding.btnToggleCase.setTextColor(if (isCaseSensitiveEnabled) primaryColor else inactiveColor)
        binding.btnToggleCase.setStrokeColorResource(
            if (isCaseSensitiveEnabled) android.R.color.holo_blue_dark else android.R.color.darker_gray
        )
    }

    private fun showSearchUI() {
        binding.searchContainer.visibility = View.VISIBLE
        binding.etSearchQuery.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        performSearch()
    }

    private fun hideSearchUI() {
        binding.searchContainer.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
        searchResults = emptyList()
        currentMatchIndex = -1
        binding.rnoteView.clearSearchResults()
        binding.searchMinimapView.clear()
    }

    private fun performSearch() {
        val doc = currentDocument ?: return
        val query = binding.etSearchQuery.text.toString()

        if (query.isBlank()) {
            searchResults = emptyList()
            currentMatchIndex = -1
            binding.tvSearchCount.text = ""
            binding.rnoteView.clearSearchResults()
            binding.searchMinimapView.clear()
            return
        }

        val options = SearchOptions(
            query = query,
            isRegex = isRegexEnabled,
            isCaseSensitive = isCaseSensitiveEnabled
        )

        searchResults = SearchEngine.search(doc, options)
        currentMatchIndex = if (searchResults.isNotEmpty()) 0 else -1

        if (searchResults.isEmpty()) {
            binding.tvSearchCount.text = getString(R.string.no_results)
            binding.rnoteView.clearSearchResults()
            binding.searchMinimapView.clear()
        } else {
            binding.tvSearchCount.text = getString(R.string.search_count, currentMatchIndex + 1, searchResults.size)
            binding.rnoteView.setSearchResults(searchResults, currentMatchIndex)
            binding.searchMinimapView.setSearchResults(searchResults, currentMatchIndex, doc.totalHeight)
        }
    }

    private fun updateMatchSelection() {
        if (searchResults.isEmpty() || currentMatchIndex < 0) return
        binding.tvSearchCount.text = getString(R.string.search_count, currentMatchIndex + 1, searchResults.size)
        binding.rnoteView.setActiveSearchResult(currentMatchIndex)
        binding.searchMinimapView.setActiveIndex(currentMatchIndex)
    }

    // ── Scrollbar ─────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollbar() {
        binding.scrollbarContainer.setOnTouchListener { _, event ->
            val container = binding.scrollbarContainer
            val progress = (event.y / container.height).coerceIn(0f, 1f)
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    binding.rnoteView.scrollToProgress(progress)
                    showPageIndicator(binding.rnoteView.currentPage())
                }
            }
            true
        }
    }

    private fun setupScrollListener() {
        binding.rnoteView.setScrollListener { progress, page, total ->
            totalPages = total
            // Aggiorna posizione thumb
            val container = binding.scrollbarContainer
            val thumbH = binding.scrollThumb.height.toFloat()
            val maxY   = (container.height - thumbH).coerceAtLeast(0f)
            binding.scrollThumb.translationY = progress * maxY
        }
    }

    /** Mostra l'indicatore pagina per 1.5 s. */
    private fun showPageIndicator(page: Int) {
        binding.pageIndicator.text = getString(R.string.page_indicator, page, totalPages)
        binding.pageIndicator.alpha = 1f
        binding.pageIndicator.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideIndicatorRunnable)
        uiHandler.postDelayed(hideIndicatorRunnable, 1500)
    }

    // ── Jump to page dialog ────────────────────

    private fun showJumpDialog() {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.jump_hint, totalPages)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.jump_title))
            .setView(input)
            .setPositiveButton(getString(R.string.go)) { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null && page in 1..totalPages) {
                    binding.rnoteView.jumpToPage(page)
                    showPageIndicator(page)
                } else {
                    Toast.makeText(this,
                        getString(R.string.jump_invalid, totalPages), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── ViewModel ─────────────────────────────

    private fun observeViewModel() {
        viewModel.loadState.observe(this) { state ->
            when (state) {
                is LoadState.Idle    -> showWelcome()
                is LoadState.Loading -> {
                    binding.welcome.visibility          = View.GONE
                    binding.progressBar.visibility      = View.VISIBLE
                    binding.rnoteView.visibility        = View.GONE
                    binding.tvLoading.visibility        = View.VISIBLE
                    binding.scrollbarContainer.visibility = View.GONE
                    binding.fabJumpPage.visibility      = View.GONE
                    binding.fabOriginPage.visibility    = View.GONE
                }
                is LoadState.Success -> {
                    binding.progressBar.visibility      = View.GONE
                    binding.tvLoading.visibility        = View.GONE
                    binding.welcome.visibility          = View.GONE
                    binding.rnoteView.visibility        = View.VISIBLE
                    binding.scrollbarContainer.visibility = View.VISIBLE
                    binding.fabJumpPage.visibility      = View.VISIBLE
                    binding.fabOriginPage.visibility    = View.VISIBLE

                    currentDocument = state.doc
                    totalPages = (state.doc.totalHeight / state.doc.pageHeight)
                        .toInt().coerceAtLeast(1)
                    supportActionBar?.title = getString(R.string.title_pages, totalPages)
                    binding.rnoteView.setDocument(state.doc, state.tree)

                    if (binding.searchContainer.isVisible) {
                        performSearch()
                    }
                }
                is LoadState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvLoading.visibility   = View.GONE
                    showWelcome()
                    Toast.makeText(this,
                        getString(R.string.error_loading, state.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showWelcome() {
        currentDocument                     = null
        hideSearchUI()
        binding.welcome.visibility          = View.VISIBLE
        binding.rnoteView.visibility        = View.GONE
        binding.progressBar.visibility      = View.GONE
        binding.tvLoading.visibility        = View.GONE
        binding.scrollbarContainer.visibility = View.GONE
        binding.fabJumpPage.visibility      = View.GONE
        binding.fabOriginPage.visibility    = View.GONE
        supportActionBar?.title             = getString(R.string.app_name)
    }

    private fun loadUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
        viewModel.loadFile(uri)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { loadUri(it) }
    }
}
