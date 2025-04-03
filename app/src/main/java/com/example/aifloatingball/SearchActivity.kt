package com.example.aifloatingball

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import com.example.aifloatingball.model.SearchEngine
import com.example.aifloatingball.view.LetterIndexBar
import net.sourceforge.pinyin4j.PinyinHelper
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.webkit.URLUtil
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import com.google.android.material.appbar.AppBarLayout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.provider.Settings
import android.graphics.Color
import com.example.aifloatingball.model.AISearchEngine
import androidx.appcompat.widget.SwitchCompat
import android.content.res.Resources

class SearchActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var letterIndexBar: LetterIndexBar
    private lateinit var letterTitle: TextView
    private lateinit var previewEngineList: LinearLayout
    private lateinit var closeButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var settingsManager: SettingsManager
    private lateinit var engineAdapter: EngineAdapter
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var engineList: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var searchEngineButton: ImageButton
    private lateinit var clearSearchButton: ImageButton
    private var currentSearchEngine: SearchEngine? = null
    
    // 修改搜索引擎集合的类型定义
    private val searchEngines = mutableListOf<SearchEngine>()
    private var modeToastView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Add flags to track receiver registration
    private var isSettingsReceiverRegistered = false
    private var isLayoutThemeReceiverRegistered = false
    
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentScale = 1f
    private var isScaling = false
    private var initialSpan = 0f
    private val MIN_SCALE_SPAN = 20f  // 降低触发阈值，让缩放更容易触发
    private val SCALE_VELOCITY_THRESHOLD = 0.02f  // 降低速度阈值，让缩放更灵敏
    private var lastScaleFactor = 1f
    private lateinit var gestureOverlay: View
    private lateinit var gestureHintView: TextView
    private var gestureHintHandler = Handler(Looper.getMainLooper())
    private var lastGestureHintRunnable: Runnable? = null
    
    // 手势状态追踪
    private var lastTapTime = 0L
    private var lastTapCount = 0
    private val DOUBLE_TAP_TIMEOUT = 300L
    private var isTwoFingerTap = false

    // 跟踪触摸点数量
    private var touchCount = 0
    private var lastTouchTime = 0L
    private val DOUBLE_TAP_TIMEOUT_TOUCH = 300L // 双指轻点的时间窗口
    
    private var searchLayout: FrameLayout? = null
    private var searchHistorySwitch: SwitchCompat? = null
    private var autoPasteSwitch: SwitchCompat? = null
    
    private lateinit var rootLayout: View
    
    // 更新开关状态
    private fun updateSwitchStates() {
        try {
            // 更新搜索历史开关状态
            searchHistorySwitch?.isChecked = settingsManager.getBoolean("search_history_enabled", true)
            searchHistorySwitch?.setOnCheckedChangeListener { _, isChecked ->
                settingsManager.putBoolean("search_history_enabled", isChecked)
            }
            
            // 更新自动粘贴开关状态
            autoPasteSwitch?.isChecked = settingsManager.isAutoPasteEnabled()
            autoPasteSwitch?.setOnCheckedChangeListener { _, isChecked ->
                settingsManager.setAutoPasteEnabled(isChecked)
            }
        } catch (e: Exception) {
            Log.e("SearchActivity", "更新开关状态时出错", e)
        }
    }
    
    // 添加搜索模式变更接收器
    private val searchModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 记录收到的所有广播，帮助调试
            Log.d("SearchActivity", "【广播接收器】收到广播: ${intent?.action}")
            Log.d("SearchActivity", "【广播接收器】Intent extras: ${intent?.extras?.keySet()?.joinToString()}")
            
            try {
                // 检查是否是测试广播
                if (intent?.getBooleanExtra("test", false) == true) {
                    Log.d("SearchActivity", "【广播接收器】收到测试广播，接收器工作正常")
                    Toast.makeText(context, "搜索模式广播接收器工作正常", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // 处理搜索模式变更广播
                if (intent?.action == "com.example.aifloatingball.SEARCH_MODE_CHANGED") {
                    val isAIMode = intent.getBooleanExtra("is_ai_mode", false)
                    Log.d("SearchActivity", "【广播接收器】收到搜索模式变更广播: ${if (isAIMode) "AI模式" else "普通模式"}")
                    
                    // 获取当前设置中的模式
                    val currentMode = settingsManager.isDefaultAIMode()
                    Log.d("SearchActivity", "【广播接收器】当前设置中的模式: ${if (currentMode) "AI模式" else "普通模式"}")
                    
                    // 确保设置和广播一致
                    if (isAIMode != currentMode) {
                        Log.d("SearchActivity", "【广播接收器】广播模式与设置不一致，使用广播中的模式")
                    }
                    
                    // 显示模式变更提示
                    showModeChangeToast(isAIMode)
                    
                    // 强制刷新搜索引擎列表
                    searchEngines.clear()
                    loadSearchEngines(forceRefresh = true)
                    
                    // 刷新字母索引栏和搜索引擎列表
                    val currentLetter = letterTitle.text?.toString()?.firstOrNull() ?: 'A'
                    Log.d("SearchActivity", "【广播接收器】重新初始化字母索引栏和引擎列表，当前字母: $currentLetter")
                    
                    // 重新初始化字母索引栏
                    runOnUiThread {
                        try {
                            // 更新字母标题
                            letterTitle.text = currentLetter.toString()
                            
                            // 加载与当前字母匹配的搜索引擎
                            showSearchEnginesByLetter(currentLetter)
                            
                            Log.d("SearchActivity", "【广播接收器】UI更新完成，模式: ${if (isAIMode) "AI模式" else "普通模式"}")
                        } catch (e: Exception) {
                            Log.e("SearchActivity", "【广播接收器】更新UI时出错", e)
                            Toast.makeText(context, "更新搜索引擎列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                // 记录接收器中的任何异常
                Log.e("SearchActivity", "【广播接收器】处理广播时出错", e)
                Toast.makeText(context, "处理模式变更广播失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    companion object {
        val NORMAL_SEARCH_ENGINES = listOf(
            SearchEngine(
                name = "功能主页",
                url = "home",  // 特殊标记，表示这是主页选项
                iconResId = R.drawable.ic_home,  // 请确保有这个图标资源
                description = "打开功能主页"
            ),
            SearchEngine(
                name = "小红书",
                url = "https://www.xiaohongshu.com/explore?keyword={query}",
                iconResId = R.drawable.ic_search,
                description = "小红书搜索"
            ),
            SearchEngine(
                name = "什么值得买",
                url = "https://search.smzdm.com/?s={query}",
                iconResId = R.drawable.ic_search,
                description = "什么值得买搜索"
            ),
            SearchEngine(
                name = "知乎",
                url = "https://www.zhihu.com/search?type=content&q={query}",
                iconResId = R.drawable.ic_search,
                description = "知乎搜索"
            ),
            SearchEngine(
                name = "GitHub",
                url = "https://github.com/search?q={query}",
                iconResId = R.drawable.ic_search,
                description = "GitHub搜索"
            ),
            SearchEngine(
                name = "CSDN",
                url = "https://so.csdn.net/so/search?q={query}",
                iconResId = R.drawable.ic_search,
                description = "CSDN搜索"
            ),
            SearchEngine(
                name = "百度",
                url = "https://www.baidu.com/s?wd={query}",
                iconResId = R.drawable.ic_search,
                description = "百度搜索"
            ),
            SearchEngine(
                name = "谷歌",
                url = "https://www.google.com/search?q={query}",
                iconResId = R.drawable.ic_search,
                description = "Google搜索"
            ),
            SearchEngine(
                name = "搜狗",
                url = "https://www.sogou.com/web?query={query}",
                iconResId = R.drawable.ic_search,
                description = "搜狗搜索"
            ),
            SearchEngine(
                name = "V2EX",
                url = "https://www.v2ex.com/search?q={query}",
                iconResId = R.drawable.ic_search,
                description = "V2EX搜索"
            ),
            SearchEngine(
                name = "今日头条",
                url = "https://so.toutiao.com/search?keyword={query}",
                iconResId = R.drawable.ic_search,
                description = "今日头条搜索"
            ),
            SearchEngine(
                name = "YouTube",
                url = "https://www.youtube.com/results?search_query={query}",
                iconResId = R.drawable.ic_search,
                description = "YouTube搜索"
            ),
            SearchEngine(
                name = "哔哩哔哩",
                url = "https://search.bilibili.com/all?keyword={query}",
                iconResId = R.drawable.ic_search,
                description = "哔哩哔哩搜索"
            ),
            SearchEngine(
                name = "X",
                url = "https://twitter.com/search?q={query}",
                iconResId = R.drawable.ic_search,
                description = "X搜索"
            )
        )

        // Add the missing constant
        const val ACTION_SHOW_SEARCH = "com.example.aifloatingball.ACTION_SHOW_SEARCH"
    }
    
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_SETTINGS_CHANGED &&
                intent.getBooleanExtra(Constants.EXTRA_LEFT_HANDED_MODE_CHANGED, false)) {
                updateLayoutForHandedness()
            }
        }
    }
    
    private val layoutThemeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.aifloatingball.LAYOUT_THEME_CHANGED") {
                // 更新 WebView 的主题
                updateWebViewTheme()
                // 更新字母索引栏和搜索引擎面板的主题
                updateLetterIndexBarTheme()
                updateEngineListTheme()
                // 重新加载当前页面以应用新主题
                webView.reload()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查是否需要使用悬浮窗模式
        if (intent.getBooleanExtra("use_floating_window", false)) {
            val url = intent.getStringExtra("url")
            if (!url.isNullOrEmpty()) {
                // 使用字母列表的悬浮窗方式打开URL
                openInLetterListFloatingWindow(url)
                finish() // 关闭当前Activity
                return
            }
        }
        
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        
        setContentView(R.layout.activity_search)
        
        settingsManager = SettingsManager.getInstance(this)
        
        // Initialize rootLayout
        rootLayout = findViewById(android.R.id.content)
        
        // 尝试查找布局和开关
        searchLayout = findViewById(R.id.webview_container) as? FrameLayout
        searchHistorySwitch = findViewById<SwitchCompat>(R.id.search_history_switch)
        autoPasteSwitch = findViewById<SwitchCompat>(R.id.auto_paste_switch)
        
        // 更新开关状态
        updateSwitchStates()
        
        try {
            // Initialize views and gesture detectors
            initViews()
            initGestureDetectors()
            
            // Register receivers
            registerReceivers()
            
            // Setup UI components
            setupWebView()
            setupLetterIndexBar()
            setupDrawer()
            updateLayoutForHandedness()

            // 检查是否是通过"应用更改"按钮启动的
            val shouldRefreshMode = intent.getBooleanExtra("refresh_mode", false)
            if (shouldRefreshMode) {
                // 从Intent获取AI模式设置
                val isAIMode = intent.getBooleanExtra("is_ai_mode", settingsManager.isDefaultAIMode())
                Log.d("SearchActivity", "通过Intent启动并刷新，设置模式为: ${if (isAIMode) "AI模式" else "普通模式"}")
                
                // 确保设置与Intent一致
                if (isAIMode != settingsManager.isDefaultAIMode()) {
                    Log.d("SearchActivity", "Intent中的模式与设置不一致，更新设置")
                    settingsManager.setDefaultAIMode(isAIMode)
                }
                
                // 显示模式切换提示
                showModeChangeToast(isAIMode)
            }

            // Load default search engine if opened from floating ball
            if (intent.getBooleanExtra("from_floating_ball", false)) {
                loadDefaultSearchEngine()
            }

            // Apply initial themes
            updateLetterIndexBarTheme()
            updateEngineListTheme()
            
            // 在初始化完成后加载搜索引擎列表
            loadSearchEngines(forceRefresh = true)

            // Check clipboard after initialization
            checkClipboard()
            
            // 打印日志，记录启动状态
            val isAIMode = settingsManager.isDefaultAIMode()
            Log.d("SearchActivity", "SearchActivity启动完成，当前模式: ${if (isAIMode) "AI模式" else "普通模式"}")
        } catch (e: Exception) {
            Log.e("SearchActivity", "Error initializing views", e)
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        webView = findViewById(R.id.web_view)
        letterIndexBar = findViewById(R.id.letter_index_bar)
        letterTitle = findViewById(R.id.letter_title)
        closeButton = findViewById(R.id.btn_close)
        menuButton = findViewById(R.id.btn_menu)
        appBarLayout = findViewById(R.id.appbar)
        engineList = findViewById(R.id.engine_list)
        previewEngineList = findViewById(R.id.preview_engine_list)
        previewEngineList.orientation = LinearLayout.VERTICAL
        progressBar = findViewById(R.id.progress_bar)
        gestureHintView = findViewById(R.id.gesture_hint)
        
        // Initialize search views
        searchInput = findViewById(R.id.search_input)
        searchEngineButton = findViewById(R.id.btn_search_engine)
        clearSearchButton = findViewById(R.id.btn_clear_search)

        // 初始化时隐藏进度条
        progressBar.visibility = View.GONE
        gestureHintView.visibility = View.GONE

        // 设置基本点击事件
        setupBasicClickListeners()
        
        // 设置搜索相关事件
        setupSearchViews()

        setupEngineList()
        
        // 获取当前搜索模式
        val isAIMode = settingsManager.isDefaultAIMode()
        Log.d("SearchActivity", "初始化视图，当前搜索模式: ${if (isAIMode) "AI模式" else "普通模式"}")
        
        // 强制更新搜索引擎列表
        loadSearchEngines(forceRefresh = true)
        
        // Initialize letter index bar - 始终使用普通搜索引擎列表以满足类型要求
        letterIndexBar.engines = NORMAL_SEARCH_ENGINES
        Log.d("SearchActivity", "字母索引栏设置了 ${NORMAL_SEARCH_ENGINES.size} 个搜索引擎")
        
        // Set initial search engine
        currentSearchEngine = NORMAL_SEARCH_ENGINES.firstOrNull()
        updateSearchEngineIcon()
    }

    private fun initGestureDetectors() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                
                val distanceX = e2.x - e1.x
                val distanceY = e2.y - e1.y
                
                // 检测水平滑动
                if (Math.abs(distanceX) > Math.abs(distanceY) && Math.abs(velocityX) > 1000) {
                    if (distanceX > 0 && webView.canGoBack()) {
                        showGestureHint("返回上一页")
                        webView.goBack()
                        return true
                    } else if (distanceX < 0 && webView.canGoForward()) {
                        showGestureHint("前进下一页")
                        webView.goForward()
                        return true
                    }
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 获取屏幕高度和点击位置
                val screenHeight = webView.height
                val tapY = e.y

                // 判断点击位置是在屏幕上半部分还是下半部分
                val scrollToTop = tapY < screenHeight / 2

                webView.evaluateJavascript("""
                    (function() {
                        window.scrollTo({
                            top: ${if (scrollToTop) "0" else "document.documentElement.scrollHeight"},
                            behavior: 'smooth'
                        });
                        return '${if (scrollToTop) "top" else "bottom"}';
                    })()
                """) { result ->
                    val destination = result?.replace("\"", "") ?: "top"
                    showGestureHint(if (destination == "top") "返回顶部" else "滚动到底部")
                }
                return true
            }
        })

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var baseScale = 1f
            private var lastSpan = 0f
            
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                baseScale = webView.scale
                lastSpan = detector.currentSpan
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 计算手指间距离的变化比例
                val spanRatio = detector.currentSpan / lastSpan
                lastSpan = detector.currentSpan
                
                // 使用比例计算新的缩放值，并添加阻尼效果
                val dampingFactor = 0.8f // 阻尼系数，使缩放更平滑
                val scaleFactor = 1f + (spanRatio - 1f) * dampingFactor
                
                val newScale = baseScale * scaleFactor
                
                // 限制缩放范围并应用缩放
                if (newScale in 0.1f..5.0f) {
                    webView.setInitialScale((newScale * 100).toInt())
                    baseScale = newScale
                    
                    // 只在缩放比例变化显著时显示提示
                    if (Math.abs(newScale - currentScale) > 0.02f) {
                        showGestureHint("缩放: ${(newScale * 100).toInt()}%")
                        currentScale = newScale
                    }
                    return true
                }
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                baseScale = webView.scale
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)

        // 处理缩放手势
        scaleGestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTapCount = 1
                lastTapTime = System.currentTimeMillis()
                isTwoFingerTap = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    lastTapCount = 2
                    isTwoFingerTap = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isTwoFingerTap && 
                    System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_TIMEOUT &&
                    !isScaling) {
                    // 双指轻点刷新
                    showGestureHint("正在刷新页面")
                    webView.reload()
                    return true
                }
            }
        }

        // 如果是双指操作或正在缩放，不传递给 WebView
        if (ev.pointerCount > 1 || isScaling) {
            return true
        }

        // 处理单指手势（滑动导航等）
        gestureDetector.onTouchEvent(ev)

        // 对于单指操作，传递给 WebView 处理滚动和点击
        return super.dispatchTouchEvent(ev)
    }

    private fun showGestureHint(message: String) {
        // 取消之前的提示
        lastGestureHintRunnable?.let { gestureHintHandler.removeCallbacks(it) }
        
        // 显示新提示
        gestureHintView.text = message
        gestureHintView.alpha = 1f
        gestureHintView.visibility = View.VISIBLE
        
        // 创建淡出动画
        gestureHintView.animate()
            .alpha(0f)
            .setDuration(1000)
            .setStartDelay(500)
            .withEndAction {
                gestureHintView.visibility = View.GONE
            }
            .start()
        
        // 设置自动隐藏
        lastGestureHintRunnable = Runnable {
            gestureHintView.visibility = View.GONE
        }
        gestureHintHandler.postDelayed(lastGestureHintRunnable!!, 1500)
    }

    private fun setupBasicClickListeners() {
        // 设置菜单按钮点击事件
        menuButton.setOnClickListener {
            val isLeftHanded = settingsManager.isLeftHandedMode()
            if (isLeftHanded) {
                if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                } else {
                    drawerLayout.openDrawer(GravityCompat.END)
                }
            } else {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }
        }

        // 设置关闭按钮点击事件
        closeButton.setOnClickListener {
            finish()
        }
        
        // 添加悬浮窗模式按钮
        val floatingModeButton = findViewById<ImageButton>(R.id.btn_floating_mode)
        if (floatingModeButton != null) {
            floatingModeButton.setOnClickListener {
                toggleFloatingMode()
            }
        }
    }

    private fun toggleFloatingMode() {
        // 检查是否有SYSTEM_ALERT_WINDOW权限
        if (!Settings.canDrawOverlays(this)) {
            // 没有权限，请求权限
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            return
        }

        // 有权限，启动悬浮窗服务
        val intent = Intent(this, FloatingWebViewService::class.java)
        
        // 获取当前URL
        val currentUrl = webView.url
        if (currentUrl != null && currentUrl != "about:blank") {
            intent.putExtra("url", currentUrl)
        } else {
            // 如果没有当前URL，使用当前搜索引擎
            currentSearchEngine?.let { engine ->
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                    val searchUrl = engine.url.replace("{query}", encodedQuery)
                    intent.putExtra("url", searchUrl)
                } else {
                    intent.putExtra("url", engine.url.replace("{query}", ""))
                }
            }
        }
        
        startService(intent)
        finish() // 关闭当前Activity
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            
            // 增加这些设置来优化缩放体验
            textZoom = 100  // 确保文本缩放正常
            defaultZoom = WebSettings.ZoomDensity.MEDIUM
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            
            // 添加新的设置
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            
            // 设置缓存模式
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // 设置 UA
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            
            // 允许混合内容
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            // 启用第三方 Cookie
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 只显示进度条，不显示全屏加载视图
                progressBar.visibility = View.VISIBLE
                Log.d("SearchActivity", "开始加载URL: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 隐藏进度条
                progressBar.visibility = View.GONE
                updateWebViewTheme()
                Log.d("SearchActivity", "页面加载完成: $url")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                
                // 只处理主页面加载错误，忽略资源加载错误
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    
                    val errorUrl = request.url?.toString() ?: "unknown"
                    val errorDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString()
                    } else {
                        "未知错误"
                    }
                    
                    Toast.makeText(this@SearchActivity, "页面加载失败", Toast.LENGTH_SHORT).show()
                    
                    // 显示更友好的错误页面
                    val errorHtml = """
                        <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <style>
                                    body { 
                                        font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                        padding: 20px;
                                        text-align: center;
                                        color: #333;
                                        background: #f5f5f5;
                                    }
                                    .error-container {
                                        background: white;
                                        border-radius: 8px;
                                        padding: 20px;
                                        margin: 20px auto;
                                        max-width: 400px;
                                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                                    }
                                    .error-icon {
                                        font-size: 48px;
                                        margin-bottom: 16px;
                                    }
                                    .error-title {
                                        color: #d32f2f;
                                        font-size: 18px;
                                        margin-bottom: 8px;
                                    }
                                    .error-message {
                                        color: #666;
                                        font-size: 14px;
                                        line-height: 1.4;
                                    }
                                    .retry-button {
                                        background: #1976d2;
                                        color: white;
                                        border: none;
                                        padding: 8px 16px;
                                        border-radius: 4px;
                                        margin-top: 16px;
                                        cursor: pointer;
                                    }
                                </style>
                            </head>
                            <body>
                                <div class="error-container">
                                    <div class="error-icon">😕</div>
                                    <div class="error-title">页面加载失败</div>
                                    <div class="error-message">
                                        抱歉，无法加载页面。请检查网络连接后重试。
                                    </div>
                                    <button class="retry-button" onclick="window.location.reload()">
                                        重新加载
                                    </button>
                                </div>
                            </body>
                        </html>
                    """.trimIndent()
                    view?.loadData(errorHtml, "text/html", "UTF-8")
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int,
                                       description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                
                // 忽略资源加载错误的提示
                if (failingUrl != view?.url) {
                    return
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    // 加载完成，隐藏进度条
                    progressBar.visibility = View.GONE
                } else {
                    // 更新加载进度
                    if (progressBar.visibility != View.VISIBLE) {
                        progressBar.visibility = View.VISIBLE
                    }
                    progressBar.progress = newProgress
                }
            }
        }

        // 初始化时设置主题
        updateWebViewTheme()
    }

    private fun updateWebViewTheme() {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.settings.forceDark = if (isDarkMode) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        } else {
            // 对于低版本 Android，在页面加载完成后注入 CSS
            if (isDarkMode) {
                webView.evaluateJavascript("""
                    (function() {
                        var darkModeStyle = document.getElementById('dark-mode-style');
                        if (!darkModeStyle) {
                            darkModeStyle = document.createElement('style');
                            darkModeStyle.id = 'dark-mode-style';
                            darkModeStyle.type = 'text/css';
                            darkModeStyle.innerHTML = `
                                :root {
                                    filter: invert(90%) hue-rotate(180deg) !important;
                                }
                                img, video, canvas, [style*="background-image"] {
                                    filter: invert(100%) hue-rotate(180deg) !important;
                                }
                                @media (prefers-color-scheme: dark) {
                                    :root {
                                        filter: none !important;
                                    }
                                    img, video, canvas, [style*="background-image"] {
                                        filter: none !important;
                                    }
                                }
                            `;
                            document.head.appendChild(darkModeStyle);
                        }
                    })()
                """.trimIndent(), null)
            } else {
                webView.evaluateJavascript("""
                    (function() {
                        var darkModeStyle = document.getElementById('dark-mode-style');
                        if (darkModeStyle) {
                            darkModeStyle.remove();
                        }
                    })()
                """.trimIndent(), null)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 配置变化时更新主题
        updateWebViewTheme()
    }

    private fun setupLetterIndexBar() {
        // 获取当前搜索模式
        val isAIMode = settingsManager.isDefaultAIMode()
        Log.d("SearchActivity", "设置字母索引栏，当前模式=${if (isAIMode) "AI模式" else "普通模式"}")
        
        // 根据当前模式设置引擎列表
        try {
            // 始终使用普通搜索引擎列表，因为字母索引栏需要的是SearchEngine类型
            letterIndexBar.engines = NORMAL_SEARCH_ENGINES
            
            if (isAIMode) {
                Log.d("SearchActivity", "当前为AI模式，但字母索引栏仍使用普通搜索引擎作为数据源")
            } else {
                Log.d("SearchActivity", "字母索引栏设置为普通搜索引擎列表: ${letterIndexBar.engines?.size}个")
            }
        } catch (e: Exception) {
            // 如果加载失败，使用普通搜索引擎列表
            Log.e("SearchActivity", "设置字母索引栏引擎列表失败", e)
            letterIndexBar.engines = NORMAL_SEARCH_ENGINES
        }

        letterIndexBar.onLetterSelectedListener = object : LetterIndexBar.OnLetterSelectedListener {
            override fun onLetterSelected(view: View, letter: Char) {
                val letterStr = letter.toString()
                letterTitle.text = letterStr
                letterTitle.visibility = View.VISIBLE
                
                // 更新搜索引擎列表，显示选中字母的搜索引擎
                showSearchEnginesByLetter(letter)
            }
        }
    }

    private fun updateEngineList(selectedLetter: Char? = null) {
        // 更新字母标题
        letterTitle.text = selectedLetter?.toString() ?: ""
        letterTitle.visibility = if (selectedLetter != null) View.VISIBLE else View.GONE
        
        // 设置字母标题的颜色和背景
        val isDarkMode = when (settingsManager.getThemeMode()) {
            SettingsManager.THEME_MODE_DARK -> true
            SettingsManager.THEME_MODE_LIGHT -> false
            else -> resources.configuration.uiMode and 
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == 
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        letterTitle.setTextColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.letter_index_text_dark
            else R.color.letter_index_text_light))
        letterTitle.setBackgroundColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.letter_index_selected_background_dark
            else R.color.letter_index_selected_background_light))

        previewEngineList.removeAllViews()

        // 获取当前搜索模式
        val isAIMode = settingsManager.isDefaultAIMode()
        Log.d("SearchActivity", "正在加载搜索引擎列表，当前模式=${if (isAIMode) "AI模式" else "普通模式"}")
        
        val engines: List<Any> = if (isAIMode) {
            // 加载AI搜索引擎列表
            try {
                val aiEngines = com.example.aifloatingball.model.AISearchEngine.DEFAULT_AI_ENGINES
                Log.d("SearchActivity", "加载AI搜索引擎列表: ${aiEngines.size}个")
                // 添加日志显示每个AI搜索引擎
                aiEngines.forEachIndexed { index, engine ->
                    Log.d("SearchActivity", "AI引擎 $index: ${engine.name} - ${engine.url}")
                }
                aiEngines
            } catch (e: Exception) {
                Log.e("SearchActivity", "加载AI搜索引擎列表失败", e)
                // 加载失败时显示提示
                Toast.makeText(this, "加载AI搜索引擎列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                // 返回空列表
                NORMAL_SEARCH_ENGINES
            }
        } else {
            // 加载普通搜索引擎列表
            Log.d("SearchActivity", "加载普通搜索引擎列表: ${NORMAL_SEARCH_ENGINES.size}个")
            NORMAL_SEARCH_ENGINES
        }
        
        // 在界面顶部添加当前模式提示
        val modeInfoText = TextView(this).apply {
            text = if (isAIMode) "当前模式: AI搜索" else "当前模式: 普通搜索"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SearchActivity,
                if (isDarkMode) R.color.letter_index_text_dark
                else R.color.letter_index_text_light))
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }
        previewEngineList.addView(modeInfoText)
        
        // 添加分隔线
        View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SearchActivity,
                if (isDarkMode) R.color.divider_dark
                else R.color.divider_light))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
            previewEngineList.addView(this)
        }
        
        val filteredEngines = if (selectedLetter != null) {
            engines.filter { engine ->
                val engineName = when (engine) {
                    is com.example.aifloatingball.model.AISearchEngine -> engine.name
                    is SearchEngine -> engine.name
                    else -> ""
                }
                
                if (engineName.isEmpty()) return@filter false
                
                val firstChar = engineName.first()
                when {
                    firstChar.toString().matches(Regex("[A-Za-z]")) -> 
                        firstChar.uppercaseChar() == selectedLetter.uppercaseChar()
                    firstChar.toString().matches(Regex("[\u4e00-\u9fa5]")) -> {
                        val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(firstChar)
                        pinyinArray?.firstOrNull()?.firstOrNull()?.uppercaseChar() == selectedLetter.uppercaseChar()
                    }
                    else -> false
                }
            }
        } else {
            engines
        }

        // 确保引擎列表可见
        previewEngineList.visibility = View.VISIBLE
        
        // 如果没有匹配的搜索引擎，显示提示信息
        if (filteredEngines.isEmpty()) {
            val noEngineText = TextView(this).apply {
                text = if (selectedLetter != null) 
                    "没有以 $selectedLetter 开头的搜索引擎"
                else
                    "当前没有可用的搜索引擎"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@SearchActivity,
                    if (isDarkMode) R.color.letter_index_text_dark
                    else R.color.letter_index_text_light))
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 32)
            }
            previewEngineList.addView(noEngineText)
            return
        }
        
        // 显示符合条件的搜索引擎列表
        filteredEngines.forEach { engine ->
            val engineItem = LayoutInflater.from(this)
                .inflate(R.layout.item_ai_engine, previewEngineList, false)

            when (engine) {
                is com.example.aifloatingball.model.AISearchEngine -> {
                    // 设置AI搜索引擎图标
                    engineItem.findViewById<ImageView>(R.id.engine_icon).apply {
                        setImageResource(engine.iconResId)
                        visibility = View.VISIBLE
                        setColorFilter(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_icon_dark
                            else R.color.engine_icon_light))
                    }
                    
                    // 设置AI搜索引擎名称
                    engineItem.findViewById<TextView>(R.id.engine_name).apply {
                        text = "${engine.name} (AI)"
                        visibility = View.VISIBLE
                        setTextColor(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_name_text_dark
                            else R.color.engine_name_text_light))
                    }
                    
                    // 设置AI搜索引擎描述
                    engineItem.findViewById<TextView>(R.id.engine_description).apply {
                        text = engine.description
                        visibility = if (engine.description.isNotEmpty()) View.VISIBLE else View.GONE
                        setTextColor(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_description_text_dark
                            else R.color.engine_description_text_light))
                    }

                    // 设置AI搜索引擎项目背景
                    engineItem.setBackgroundColor(ContextCompat.getColor(this,
                        if (isDarkMode) R.color.engine_list_background_dark
                        else R.color.engine_list_background_light))

                    // 设置AI搜索引擎点击事件
                    engineItem.setOnClickListener {
                        // 为AI搜索引擎创建一个SearchEngine对象
                        val searchEngine = SearchEngine(
                            name = engine.name,
                            url = engine.url,
                            iconResId = engine.iconResId,
                            description = engine.description
                        )
                        openSearchEngine(searchEngine)
                        drawerLayout.closeDrawer(if (settingsManager.isLeftHandedMode()) GravityCompat.END else GravityCompat.START)
                    }

                    engineItem.setOnLongClickListener {
                        // 为AI搜索引擎创建一个SearchEngine对象
                        val searchEngine = SearchEngine(
                            name = engine.name,
                            url = engine.url,
                            iconResId = engine.iconResId,
                            description = engine.description
                        )
                        showEngineSettings(searchEngine)
                        true
                    }
                }
                
                is SearchEngine -> {
                    // 设置普通搜索引擎图标
                    engineItem.findViewById<ImageView>(R.id.engine_icon).apply {
                        setImageResource(engine.iconResId)
                        visibility = View.VISIBLE
                        setColorFilter(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_icon_dark
                            else R.color.engine_icon_light))
                    }
                    
                    // 设置普通搜索引擎名称
                    engineItem.findViewById<TextView>(R.id.engine_name).apply {
                        text = engine.name
                        visibility = View.VISIBLE
                        setTextColor(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_name_text_dark
                            else R.color.engine_name_text_light))
                    }
                    
                    // 设置普通搜索引擎描述
                    engineItem.findViewById<TextView>(R.id.engine_description).apply {
                        text = engine.description
                        visibility = if (engine.description.isNotEmpty()) View.VISIBLE else View.GONE
                        setTextColor(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.engine_description_text_dark
                            else R.color.engine_description_text_light))
                    }

                    // 设置普通搜索引擎项目背景
                    engineItem.setBackgroundColor(ContextCompat.getColor(this,
                        if (isDarkMode) R.color.engine_list_background_dark
                        else R.color.engine_list_background_light))

                    // 设置普通搜索引擎点击事件
                    engineItem.setOnClickListener {
                        openSearchEngine(engine)
                        drawerLayout.closeDrawer(if (settingsManager.isLeftHandedMode()) GravityCompat.END else GravityCompat.START)
                    }

                    engineItem.setOnLongClickListener {
                        showEngineSettings(engine)
                        true
                    }
                }
            }

            // 添加到列表中
            previewEngineList.addView(engineItem)
            
            // 添加分隔线
            if (filteredEngines.last() != engine) {
                View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@SearchActivity,
                        if (isDarkMode) R.color.divider_dark
                        else R.color.divider_light))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    previewEngineList.addView(this)
                }
            }
        }
    }

    // EngineAdapter class
    private inner class EngineAdapter(
        private var engines: List<SearchEngine>,
        private val onEngineClick: (SearchEngine) -> Unit
    ) : RecyclerView.Adapter<EngineAdapter.ViewHolder>() {
        
        fun updateEngines(newEngines: List<SearchEngine>) {
            engines = newEngines
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_engine, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val engine = engines[position]
            holder.bind(engine)
        }
        
        override fun getItemCount() = engines.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.engine_icon)
            private val name: TextView = itemView.findViewById(R.id.engine_name)
            private val description: TextView = itemView.findViewById(R.id.engine_description)
            
            fun bind(engine: SearchEngine) {
                icon.setImageResource(engine.iconResId)
                name.text = engine.name
                description.text = engine.description
                
                itemView.setOnClickListener {
                        onEngineClick(engine)
                }
                
                itemView.setOnLongClickListener {
                    showEngineSettings(engine)
                    true
                }
            }
        }
    }

    private fun showEngineSettings(engine: SearchEngine) {
        val options = arrayOf("访问主页", "在悬浮窗中打开", "复制链接", "分享", "在浏览器中打开")

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${engine.name} 选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSearchEngine(engine)
                    1 -> {
                        // 在悬浮窗中打开
                        settingsManager.putBoolean("use_floating_mode", true)
                        openSearchEngine(engine)
                        settingsManager.putBoolean("use_floating_mode", false) // 重置为默认值
                    }
                    2 -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", engine.url))
                        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${engine.name}: ${engine.url}")
                        }
                        startActivity(Intent.createChooser(intent, "分享到"))
                    }
                    4 -> {
                        val intent = Intent(Intent.ACTION_VIEW, 
                            android.net.Uri.parse(engine.url))
                        startActivity(intent)
                    }
                }
            }
            .create()
        dialog.show()
    }

    private fun loadDefaultSearchEngine() {
        val defaultEngine = settingsManager.getString(Constants.PREF_DEFAULT_SEARCH_ENGINE, "") ?: ""
        if (defaultEngine.isNotEmpty()) {
            val parts = defaultEngine.split("|")
            if (parts.size >= 2) {
                val engineName = parts[0]
                val engine = NORMAL_SEARCH_ENGINES.find { it.name == engineName }
                if (engine != null) {
                    currentSearchEngine = engine
                    updateSearchEngineIcon()
                }
            }
        }
        
        // If no valid engine was found or no default was set, use the first engine
        if (currentSearchEngine == null) {
            currentSearchEngine = NORMAL_SEARCH_ENGINES.firstOrNull()
            updateSearchEngineIcon()
        }
    }

    private fun updateSearchEngineIcon() {
        currentSearchEngine?.let { engine ->
            searchEngineButton.setImageResource(engine.iconResId)
        }
    }

    private fun openSearchEngine(engine: SearchEngine) {
        currentSearchEngine = engine
        updateSearchEngineIcon()
        
        // 如果是主页选项，则返回主页
        if (engine.url == "home") {
            val intent = Intent(this, HomeActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // 检查是否应该使用悬浮窗模式
        val useFloatingMode = settingsManager.getBoolean("use_floating_mode", false)
        
        if (useFloatingMode) {
            // 检查是否有SYSTEM_ALERT_WINDOW权限
            if (!Settings.canDrawOverlays(this)) {
                // 没有权限，请求权限
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                return
            }
            
            // 有权限，启动悬浮窗服务
            val intent = Intent(this, FloatingWebViewService::class.java)
            
            // 获取当前搜索词
            val query = searchInput.text.toString().trim()
            
            if (query.isEmpty()) {
                // 如果没有查询文本，直接打开搜索引擎主页
                intent.putExtra("url", engine.url.replace("{query}", "").replace("search?q=", "")
                    .replace("search?query=", "")
                    .replace("search?word=", "")
                    .replace("s?wd=", ""))
            } else {
                // 有查询文本，使用搜索引擎进行搜索
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = engine.url.replace("{query}", encodedQuery)
                intent.putExtra("url", searchUrl)
            }
            
            startService(intent)
            finish() // 关闭当前Activity
        } else {
            // 使用普通模式
            // 获取当前搜索词
            val query = searchInput.text.toString().trim()
            
            // 如果搜索框不为空，则直接搜索
            if (query.isNotEmpty()) {
                performSearch(query)
            } else {
                // 否则加载搜索引擎主页
                loadSearchEngineHomepage(engine)
            }
        }
    }

    private fun loadSearchEngineHomepage(engine: SearchEngine) {
        val baseUrl = engine.url.split("?")[0]
        webView.loadUrl(baseUrl)
    }

    private fun updateLayoutForHandedness() {
        val isLeftHanded = settingsManager.isLeftHandedMode()
        
        // 更新抽屉位置
        (drawerLayout.getChildAt(1) as? LinearLayout)?.let { drawer ->
            // 先关闭抽屉，避免切换时的动画问题
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            }
            
            // 更新抽屉位置
            drawer.layoutParams = (drawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = if (isLeftHanded) Gravity.END else Gravity.START
            }
        }

        // 更新菜单按钮位置
        val leftButtons = findViewById<LinearLayout>(R.id.left_buttons)
        val rightButtons = findViewById<LinearLayout>(R.id.right_buttons)
        val menuButton = findViewById<ImageButton>(R.id.btn_menu)

        // 从当前父容器中移除菜单按钮
        (menuButton.parent as? ViewGroup)?.removeView(menuButton)

        if (isLeftHanded) {
            // 左手模式：将菜单按钮添加到右侧按钮容器的开始位置
            rightButtons.addView(menuButton, 0)
        } else {
            // 右手模式：将菜单按钮添加到左侧按钮容器
            leftButtons.addView(menuButton)
        }
    }

    override fun onBackPressed() {
        when {
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            if (isSettingsReceiverRegistered) {
                unregisterReceiver(settingsReceiver)
                isSettingsReceiverRegistered = false
            }
            if (isLayoutThemeReceiverRegistered) {
                unregisterReceiver(layoutThemeReceiver)
                isLayoutThemeReceiverRegistered = false
            }
            // 取消注册搜索模式变更接收器
            try {
                unregisterReceiver(searchModeReceiver)
            } catch (e: Exception) {
                // 如果接收器未注册，忽略异常
                Log.e("SearchActivity", "取消注册searchModeReceiver失败", e)
            }
        } catch (e: Exception) {
            Log.e("SearchActivity", "Error unregistering receivers", e)
        }
        super.onDestroy()
    }

    private fun updateLetterIndexBarTheme() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        
        // 设置深色模式状态
        letterIndexBar.setDarkMode(isDarkMode)
        
        // 设置背景色
        letterIndexBar.setBackgroundColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.letter_index_background_dark
            else R.color.letter_index_background_light))
            
        // 设置字母标题的颜色和背景
        letterTitle.setTextColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.letter_index_text_dark
            else R.color.letter_index_text_light))
        letterTitle.setBackgroundColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.letter_index_selected_background_dark
            else R.color.letter_index_selected_background_light))
    }

    private fun updateEngineListTheme() {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES

        // 更新抽屉布局背景色
        (drawerLayout.getChildAt(1) as? LinearLayout)?.apply {
            setBackgroundColor(ContextCompat.getColor(this@SearchActivity,
                if (isDarkMode) R.color.engine_list_background_dark
                else R.color.engine_list_background_light))
        }

        // 更新搜索引擎列表的背景色
        previewEngineList.setBackgroundColor(ContextCompat.getColor(this,
            if (isDarkMode) R.color.engine_list_background_dark
            else R.color.engine_list_background_light))

        // 更新每个搜索引擎项的颜色
        for (i in 0 until previewEngineList.childCount) {
            val child = previewEngineList.getChildAt(i)
            if (child is ViewGroup) {
                // 更新引擎名称文本颜色
                child.findViewById<TextView>(R.id.engine_name)?.apply {
                    setTextColor(ContextCompat.getColor(this@SearchActivity,
                        if (isDarkMode) R.color.engine_name_text_dark
                        else R.color.engine_name_text_light))
                }

                // 更新引擎描述文本颜色
                child.findViewById<TextView>(R.id.engine_description)?.apply {
                    setTextColor(ContextCompat.getColor(this@SearchActivity,
                        if (isDarkMode) R.color.engine_description_text_dark
                        else R.color.engine_description_text_light))
                }

                // 更新图标颜色
                child.findViewById<ImageView>(R.id.engine_icon)?.apply {
                    setColorFilter(ContextCompat.getColor(this@SearchActivity,
                        if (isDarkMode) R.color.engine_icon_dark
                        else R.color.engine_icon_light))
                }

                // 更新整个项目的背景
                child.setBackgroundColor(ContextCompat.getColor(this,
                    if (isDarkMode) R.color.engine_list_background_dark
                    else R.color.engine_list_background_light))
            } else if (child is View && child.layoutParams.height == 1) {
                // 更新分隔线颜色
                child.setBackgroundColor(ContextCompat.getColor(this,
                    if (isDarkMode) R.color.divider_dark
                    else R.color.divider_light))
            }
        }

        // 强制重绘整个列表
        previewEngineList.invalidate()
    }

    private fun setupDrawer() {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            
            override fun onDrawerOpened(drawerView: View) {
                updateEngineList()
            }
            
            override fun onDrawerClosed(drawerView: View) {}
            
            override fun onDrawerStateChanged(newState: Int) {}
        })

        // 根据当前模式设置初始抽屉位置
        val isLeftHanded = settingsManager.isLeftHandedMode()
        (drawerLayout.getChildAt(1) as? LinearLayout)?.let { drawer ->
            drawer.layoutParams = (drawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = if (isLeftHanded) Gravity.END else Gravity.START
            }
        }
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun setupEngineList() {
        // Set layout manager
        engineList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        // Initialize adapter with empty list
        engineAdapter = EngineAdapter(
            engines = emptyList(),
            onEngineClick = { engine ->
                openSearchEngine(engine)
                drawerLayout.closeDrawer(if (settingsManager.isLeftHandedMode()) GravityCompat.END else GravityCompat.START)
            }
        )
        
        // Attach adapter to RecyclerView
        engineList.adapter = engineAdapter
        
        // Load initial engines
        updateEngineAdapter()
        
        // Register for search mode changes
        settingsManager.registerOnSettingChangeListener<Boolean>("default_search_mode") { _, isAIMode ->
            // Update adapter when search mode changes
            updateEngineAdapter()
        }
    }
    
    private fun updateEngineAdapter() {
        val isAIMode = settingsManager.isDefaultAIMode()
        val engines = if (isAIMode) {
            // Convert AI engines to SearchEngine type for adapter
            com.example.aifloatingball.model.AISearchEngine.DEFAULT_AI_ENGINES.map { aiEngine ->
                SearchEngine(
                    name = "${aiEngine.name} (AI)",
                    url = aiEngine.url,
                    iconResId = aiEngine.iconResId,
                    description = aiEngine.description
                )
            }
        } else {
            NORMAL_SEARCH_ENGINES
        }
        
        Log.d("SearchActivity", "更新引擎适配器: ${if (isAIMode) "AI模式" else "普通模式"}, ${engines.size}个引擎")
        engineAdapter.updateEngines(engines)
    }

    private fun checkClipboard() {
        if (intent.getBooleanExtra("from_floating_ball", false)) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                val clipText = clipData?.getItemAt(0)?.text?.toString()?.trim()
                
                if (!clipText.isNullOrEmpty()) {
                    showClipboardDialog(clipText)
                }
            }
        }
    }

    private fun showClipboardDialog(content: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.overlay_dialog, null)
        
        // 设置内容
        dialogView.findViewById<TextView>(R.id.dialog_title).text = "检测到剪贴板内容"
        dialogView.findViewById<TextView>(R.id.dialog_message).text = content
        
        // 创建对话框
        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()
        
        // 设置按钮点击事件
        if (URLUtil.isValidUrl(content)) {
            dialogView.findViewById<Button>(R.id.btn_open_link).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    dialog.dismiss()
                    openUrl(content)
                }
            }
        }
        
        dialogView.findViewById<Button>(R.id.btn_search).setOnClickListener {
            dialog.dismiss()
            searchContent(content)
        }
        
        dialogView.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        
        // 显示对话框
        dialog.show()
        
        // 设置自动关闭
        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 5000)
    }
    
    private fun openUrl(url: String) {
        try {
            val intent = Intent(this, FullscreenWebViewActivity::class.java).apply {
                putExtra("url", url)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开URL失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun searchContent(query: String) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.baidu.com/s?wd=$encodedQuery"
            openUrl(searchUrl)
        } catch (e: Exception) {
            Toast.makeText(this, "搜索内容失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerReceivers() {
        try {
            // Register settings change receiver
            val settingsFilter = IntentFilter(Constants.ACTION_SETTINGS_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(settingsReceiver, settingsFilter)
            }
            isSettingsReceiverRegistered = true
            
            // Register layout theme change receiver
            val themeFilter = IntentFilter("com.example.aifloatingball.LAYOUT_THEME_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(layoutThemeReceiver, themeFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(layoutThemeReceiver, themeFilter)
            }
            isLayoutThemeReceiverRegistered = true
            
            // 注册搜索模式变更广播接收器
            val searchModeFilter = IntentFilter()
            searchModeFilter.addAction("com.example.aifloatingball.SEARCH_MODE_CHANGED")
            searchModeFilter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(searchModeReceiver, searchModeFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(searchModeReceiver, searchModeFilter)
            }
            
            // 发送测试广播以验证接收器是否正常工作
            val testIntent = Intent("com.example.aifloatingball.SEARCH_MODE_CHANGED")
            testIntent.putExtra("test", true)
            sendBroadcast(testIntent)
            
            Log.d("SearchActivity", "搜索模式变更广播接收器注册成功, 当前类: ${this.javaClass.name}, 包名: ${this.packageName}")
            Log.d("SearchActivity", "当前AI模式状态: ${if(settingsManager.isDefaultAIMode()) "开启" else "关闭"}")
        } catch (e: Exception) {
            Log.e("SearchActivity", "Error registering receivers", e)
        }
    }

    private fun setupSearchViews() {
        // 设置搜索引擎按钮点击事件
        searchEngineButton.setOnClickListener {
            showSearchEngineSelector()
        }

        // 设置清除按钮点击事件
        clearSearchButton.setOnClickListener {
            searchInput.setText("")
            clearSearchButton.visibility = View.GONE
        }

        // 设置搜索框文本变化监听
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                clearSearchButton.visibility = if (s?.isNotEmpty() == true) View.VISIBLE else View.GONE
            }
        })

        // 设置搜索动作监听
        searchInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val query = searchInput.text.toString().trim()
                    if (query.isNotEmpty()) {
                        performSearch(query)
                    // 隐藏键盘
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
                    return@setOnEditorActionListener true
                    }
            }
                    false
                }
            }

    private fun showSearchEngineSelector() {
        val engines = NORMAL_SEARCH_ENGINES
        val engineNames = engines.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("选择搜索引擎")
            .setItems(engineNames) { _, which ->
                val selectedEngine = engines[which]
                openSearchEngine(selectedEngine)
            }
            .show()
    }

    private fun performSearch(query: String) {
        val searchEngine = currentSearchEngine ?: return
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = searchEngine.url.replace("{query}", encodedQuery)
        webView.loadUrl(searchUrl)
    }

    // 添加加载搜索引擎的方法
    private fun loadSearchEngines(forceRefresh: Boolean = false) {
        // 如果强制刷新，则清空现有列表
        if (forceRefresh) {
            searchEngines.clear()
            Log.d("SearchActivity", "强制刷新，已清空搜索引擎列表")
        }
        
        // 如果列表不为空且不是强制刷新，则无需重新加载
        if (searchEngines.isNotEmpty() && !forceRefresh) {
            Log.d("SearchActivity", "搜索引擎列表已存在，无需重新加载")
            return
        }
        
        try {
            // 获取当前搜索模式
            val isAIMode = settingsManager.isDefaultAIMode()
            Log.d("SearchActivity", "正在加载搜索引擎列表，当前模式=${if (isAIMode) "AI模式" else "普通模式"}")
            
            // 清空现有列表
            searchEngines.clear()
            
            if (isAIMode) {
                // 将AI搜索引擎转换为SearchEngine对象
                val aiEngines = com.example.aifloatingball.model.AISearchEngine.DEFAULT_AI_ENGINES
                Log.d("SearchActivity", "加载AI搜索引擎列表: ${aiEngines.size}个")
                
                aiEngines.forEach { aiEngine ->
                    searchEngines.add(SearchEngine(
                        name = "${aiEngine.name} (AI)",
                        url = aiEngine.url,
                        iconResId = aiEngine.iconResId,
                        description = aiEngine.description
                    ))
                    Log.d("SearchActivity", "已添加AI引擎: ${aiEngine.name}")
                }
            } else {
                // 直接加载普通搜索引擎列表
                Log.d("SearchActivity", "加载普通搜索引擎列表: ${NORMAL_SEARCH_ENGINES.size}个")
                searchEngines.addAll(NORMAL_SEARCH_ENGINES)
            }
        } catch (e: Exception) {
            // 如果加载失败，显示错误信息
            Log.e("SearchActivity", "加载搜索引擎列表失败: ${e.message}", e)
            Toast.makeText(this, "加载搜索引擎列表失败: ${e.message}", Toast.LENGTH_LONG).show()
            
            // 加载一个默认引擎，确保应用不会崩溃
            searchEngines.add(SearchEngine(
                name = "百度",
                url = "https://www.baidu.com/s?wd={query}",
                iconResId = R.drawable.ic_search,
                description = "百度搜索"
            ))
            Toast.makeText(this, "已加载默认搜索引擎: 百度", Toast.LENGTH_SHORT).show()
        }
    }

    // 添加模式切换提示
    private fun showModeChangeToast(isAIMode: Boolean) {
        try {
            // 移除旧的Toast视图
            removeModeToast()
            
            // 创建自定义Toast视图
            val inflater = LayoutInflater.from(this)
            val toastView = inflater.inflate(R.layout.toast_mode_change, null)
            
            // 设置Toast的图标和文本
            val iconView = toastView.findViewById<ImageView>(R.id.toast_icon)
            val messageView = toastView.findViewById<TextView>(R.id.toast_message)
            
            if (isAIMode) {
                iconView.setImageResource(R.drawable.ic_chatgpt) // 使用已存在的图标
                messageView.text = "AI搜索模式已启用"
                toastView.setBackgroundResource(R.color.ai_mode_background)
            } else {
                iconView.setImageResource(R.drawable.ic_search)
                messageView.text = "普通搜索模式已启用"
                toastView.setBackgroundResource(R.color.normal_mode_background)
            }
            
            // 创建并显示自定义Toast
            val toast = Toast(applicationContext)
            toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150)
            toast.duration = Toast.LENGTH_LONG
            toast.view = toastView
            toast.show()
            
            // 保存Toast视图引用
            modeToastView = toastView
            
            // 设置定时器移除Toast
            handler.postDelayed({
                removeModeToast()
            }, 3000)
            
            Log.d("SearchActivity", "显示模式切换提示: ${if (isAIMode) "AI模式" else "普通模式"}")
        } catch (e: Exception) {
            Log.e("SearchActivity", "显示模式切换提示失败", e)
            // 使用系统Toast作为备选
            Toast.makeText(
                this,
                if (isAIMode) "已切换到AI搜索模式" else "已切换到普通搜索模式",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun removeModeToast() {
        modeToastView?.let {
            // 如果存在旧的Toast视图，尝试移除
            try {
                (it.parent as? ViewGroup)?.removeView(it)
            } catch (e: Exception) {
                Log.e("SearchActivity", "移除Toast视图失败", e)
            }
            modeToastView = null
        }
    }

    // 添加显示特定字母搜索引擎的方法
    private fun showSearchEnginesByLetter(letter: Char) {
        // Update letter title
        letterTitle.text = letter.toString()
        
        // Get theme colors
        val isDarkMode = when (settingsManager.getThemeMode()) {
            SettingsManager.THEME_MODE_DARK -> true
            SettingsManager.THEME_MODE_LIGHT -> false
            else -> resources.configuration.uiMode and 
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == 
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        
        // 获取正确的文本颜色
        val textColorRes = if (isDarkMode) {
            R.color.engine_name_text_dark // 深色模式下的浅色文本
        } else {
            R.color.engine_name_text_light // 浅色模式下的深色文本
        }
        
        val textColor = ContextCompat.getColor(this, textColorRes)

        // Clear engine list
        previewEngineList.removeAllViews()

        // 显示搜索模式信息
        val isAIMode = settingsManager.isDefaultAIMode()
        val modeInfoText = TextView(this).apply {
            text = if (isAIMode) "当前模式: AI搜索" else "当前模式: 普通搜索"
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            
            // 设置背景颜色指示当前模式
            setBackgroundResource(if (isAIMode) R.color.ai_mode_indicator_bg else R.color.normal_mode_indicator_bg)
        }
        previewEngineList.addView(modeInfoText)
        
        // 添加分隔线
        val modeDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ContextCompat.getColor(this@SearchActivity,
                if (isDarkMode) R.color.divider_dark else R.color.divider_light))
        }
        previewEngineList.addView(modeDivider)

        // 记录搜索引擎列表中的引擎类型，用于调试
        val aiCount = searchEngines.count { it is AISearchEngine }
        val normalCount = searchEngines.count { it is SearchEngine }
        Log.d("SearchActivity", "搜索引擎列表: AI引擎=${aiCount}个, 普通引擎=${normalCount}个, 总计=${searchEngines.size}个")
        Log.d("SearchActivity", "当前搜索模式: ${if (isAIMode) "AI模式" else "普通模式"}")

        // 查找所有匹配该字母的搜索引擎
        val matchingEngines = searchEngines.filter { engine ->
            val engineName = engine.name
            
            if (engineName.isEmpty()) return@filter false
            
            val firstChar = engineName.first()
            if (firstChar.toString().matches(Regex("[\u4e00-\u9fa5]"))) {
                PinyinHelper.toHanyuPinyinStringArray(firstChar)?.firstOrNull()?.first() == letter.lowercaseChar()
            } else {
                firstChar.lowercaseChar() == letter.lowercaseChar()
            }
        }

        Log.d("SearchActivity", "匹配字母 '$letter' 的引擎数量: ${matchingEngines.size}")

        if (matchingEngines.isEmpty()) {
            // 如果没有匹配的搜索引擎，显示提示信息
            val noEngineText = TextView(this).apply {
                text = "没有以 $letter 开头的搜索引擎"
                textSize = 16f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 32)
            }
            previewEngineList.addView(noEngineText)
        } else {
            // 添加匹配的搜索引擎
            matchingEngines.forEach { engine ->
                val engineItem = LayoutInflater.from(this).inflate(
                    R.layout.item_search_engine,
                    previewEngineList,
                    false
                )

                // 获取引擎信息
                val engineName = engine.name
                val engineUrl = engine.url
                val engineIcon = engine.iconResId

                // Set search engine icon with theme color
                engineItem.findViewById<ImageView>(R.id.engine_icon).apply {
                    setImageResource(engineIcon)
                    setColorFilter(textColor) // 使用正确的文本颜色
                }

                // Set search engine name with theme color
                engineItem.findViewById<TextView>(R.id.engine_name).apply {
                    text = engineName
                    setTextColor(textColor) // 使用正确的文本颜色
                }

                // 设置引擎描述文本（如果有）
                engineItem.findViewById<TextView>(R.id.engine_description)?.apply {
                    text = engine.description
                    visibility = if (engine.description.isNotEmpty()) View.VISIBLE else View.GONE
                    setTextColor(textColor) // 使用正确的文本颜色
                }

                // 设置项目背景
                engineItem.setBackgroundColor(ContextCompat.getColor(this,
                    if (isDarkMode) R.color.engine_list_background_dark
                    else R.color.engine_list_background_light))

                // 添加点击事件
                engineItem.setOnClickListener {
                    val intent = Intent(this, FloatingWindowService::class.java).apply {
                        action = ACTION_SHOW_SEARCH
                        putExtra("ENGINE_NAME", engineName)
                        putExtra("ENGINE_URL", engineUrl)
                        putExtra("ENGINE_ICON", engineIcon)
                        putExtra("SEARCH_QUERY", searchInput.text.toString())
                    }
                    startService(intent)
                    finish()
                }

                previewEngineList.addView(engineItem)

                // 在每个搜索引擎项之间添加分隔线
                if (engine != matchingEngines.last()) {
                    val itemDivider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1
                        )
                        setBackgroundColor(ContextCompat.getColor(this@SearchActivity,
                            if (isDarkMode) R.color.divider_dark else R.color.divider_light))
                    }
                    previewEngineList.addView(itemDivider)
                }
            }
        }
    }

    private fun openInLetterListFloatingWindow(url: String) {
        try {
            // 创建与字母列表长按相同的悬浮窗Intent
            val intent = Intent(this, FloatingWebViewService::class.java).apply {
                putExtra("url", url)
                putExtra("from_ai_menu", true) // 标记来源，以便特殊处理
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("SearchActivity", "打开悬浮窗失败", e)
            Toast.makeText(this, "打开悬浮窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 更新布局位置
    private fun updateLayoutPosition() {
        val isLeftHanded = settingsManager.isLeftHandedMode()
        val layoutParams = searchLayout?.layoutParams as? FrameLayout.LayoutParams
        
        layoutParams?.gravity = if (isLeftHanded) Gravity.START else Gravity.END
        searchLayout?.layoutParams = layoutParams
    }

    // 保存设置
    private fun saveSettings() {
        // 使用 SettingsManager 直接保存设置，不依赖 UI 组件
        val searchHistoryEnabled = searchHistorySwitch?.isChecked ?: settingsManager.getBoolean("search_history_enabled", true)
        val autoPasteEnabled = autoPasteSwitch?.isChecked ?: settingsManager.getBoolean("auto_paste_enabled", true)
        
        settingsManager.putBoolean("search_history_enabled", searchHistoryEnabled)
        settingsManager.putBoolean("auto_paste_enabled", autoPasteEnabled)
    }

    // 加载设置
    private fun loadSettings() {
        // 如果 UI 组件存在，则将设置值加载到它们上
        searchHistorySwitch?.isChecked = settingsManager.getBoolean("search_history_enabled", true)
        autoPasteSwitch?.isChecked = settingsManager.getBoolean("auto_paste_enabled", true)
        
        // 加载默认搜索引擎
        val defaultEngine = settingsManager.getString("default_search_engine", "baidu")
        updateDefaultSearchEngine(defaultEngine ?: "baidu")
    }

    // 更新主题
    private fun updateTheme() {
        try {
            when (settingsManager.getThemeMode()) {
                SettingsManager.THEME_MODE_SYSTEM -> {
                    // 使用默认主题
                    window.statusBarColor = getColor(R.color.colorPrimaryDark)
                    window.navigationBarColor = getColor(R.color.colorPrimaryDark)
                    rootLayout?.setBackgroundColor(getColor(R.color.colorBackground))
                }
                SettingsManager.THEME_MODE_DEFAULT -> {
                    // 使用默认主题
                    window.statusBarColor = getColor(R.color.colorPrimaryDark)
                    window.navigationBarColor = getColor(R.color.colorPrimaryDark)
                    searchLayout?.setBackgroundColor(getColor(R.color.colorBackground))
                }
                SettingsManager.THEME_MODE_LIGHT -> {
                    // 使用浅色主题
                    window.statusBarColor = getColor(R.color.colorLightPrimaryDark)
                    window.navigationBarColor = getColor(R.color.colorLightPrimaryDark)
                    searchLayout?.setBackgroundColor(getColor(R.color.colorLightBackground))
                }
                SettingsManager.THEME_MODE_DARK -> {
                    // 使用深色主题
                    window.statusBarColor = getColor(R.color.colorDarkPrimaryDark)
                    window.navigationBarColor = getColor(R.color.colorDarkPrimaryDark)
                    searchLayout?.setBackgroundColor(getColor(R.color.colorDarkBackground))
                }
            }
        } catch (e: Resources.NotFoundException) {
            // 如果颜色资源不存在，使用默认颜色
            Log.e("SearchActivity", "Error applying theme: ${e.message}")
            window.statusBarColor = Color.parseColor("#1976D2") // Default blue
            searchLayout?.setBackgroundColor(Color.WHITE)
        }
    }

    // 更新默认搜索引擎
    private fun updateDefaultSearchEngine(engine: String) {
        try {
            // 保存设置
            settingsManager.setDefaultSearchEngine(engine)
            
            // 查找匹配的搜索引擎并更新当前搜索引擎
            val searchEngine = NORMAL_SEARCH_ENGINES.find { it.name == engine }
            if (searchEngine != null) {
                currentSearchEngine = searchEngine
                updateSearchEngineIcon()
                
                // 显示提示
                Toast.makeText(this, "默认搜索引擎已设置为: ${searchEngine.name}", Toast.LENGTH_SHORT).show()
            } else {
                // 如果找不到匹配的搜索引擎，使用第一个
                currentSearchEngine = NORMAL_SEARCH_ENGINES.firstOrNull()
                updateSearchEngineIcon()
                
                // 显示错误提示
                Toast.makeText(this, "无法找到搜索引擎: $engine，已使用默认值", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SearchActivity", "更新默认搜索引擎失败", e)
            Toast.makeText(this, "更新默认搜索引擎失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Update default search engine
    private fun updateDefaultSearchEngine() {
        val defaultEngine = settingsManager.getString(Constants.PREF_DEFAULT_SEARCH_ENGINE, Constants.DEFAULT_SEARCH_ENGINE)
        currentSearchEngine = searchEngines.find { engine ->
            when (engine) {
                is AISearchEngine -> engine.name == defaultEngine
                is SearchEngine -> engine.name == defaultEngine
                else -> false
            }
        } ?: searchEngines.firstOrNull()
        
        updateSearchEngineIcon()
    }

    // Send settings changed broadcast
    private fun notifySettingsChanged(leftHandedModeChanged: Boolean = false) {
        val intent = Intent(Constants.ACTION_SETTINGS_CHANGED).apply {
            putExtra(Constants.EXTRA_LEFT_HANDED_MODE_CHANGED, leftHandedModeChanged)
        }
        sendBroadcast(intent)
    }
} 