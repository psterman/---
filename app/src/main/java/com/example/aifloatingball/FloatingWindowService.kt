package com.example.aifloatingball

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable 
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aifloatingball.gesture.GestureManager
import com.example.aifloatingball.menu.QuickMenuManager
import com.example.aifloatingball.search.SearchHistoryAdapter
import com.example.aifloatingball.search.SearchHistoryManager
import com.example.aifloatingball.utils.SystemSettingsHelper
import com.example.aifloatingball.web.CustomWebViewClient
import kotlin.math.abs
import kotlin.math.min
import com.example.aifloatingball.SearchActivity
import com.example.aifloatingball.HomeActivity
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import kotlin.math.sqrt
import android.content.ClipboardManager
import android.webkit.URLUtil
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import com.example.aifloatingball.model.MenuItem
import com.example.aifloatingball.model.MenuCategory
import com.example.aifloatingball.utils.IconLoader
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.widget.GridLayout
import kotlin.math.ceil
import java.net.URLEncoder
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.graphics.Typeface

class FloatingWindowService : Service(), GestureManager.GestureCallback {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val MEMORY_CHECK_INTERVAL = 30000L // 30秒检查一次内存
        private const val REQUEST_SCREENSHOT = 1001
        private const val EDGE_SNAP_THRESHOLD = 32f // dp
        private const val FLING_MIN_VELOCITY = 500f // 最小甩动速度
        private const val ANIMATION_DURATION = 250L // 动画时长
        private const val TAG = "FloatingService" // 添加 TAG 常量
    }

    protected var windowManager: WindowManager? = null
    private var floatingBallView: View? = null
    protected lateinit var gestureManager: GestureManager
    protected lateinit var quickMenuManager: QuickMenuManager
    protected lateinit var systemSettingsHelper: SystemSettingsHelper
    protected lateinit var iconLoader: IconLoader
    protected var screenWidth = 0
    protected var screenHeight = 0
    private var currentEngineIndex = 0
    
    private var searchBoxView: View? = null
    private var isSearchBoxVisible = false
    
    private var recognizer: SpeechRecognizer? = null
    
    private var isCardViewMode = false
    private var cardStartY = 0f
    private var cardStartX = 0f
    private var currentCardIndex = 0
    private var cardSpacing = 0
    private var cardRotation = 10f
    private var cardScale = 0.9f
    
    // 添加手势检测相关变量
    private var initialTouchY = 0f
    private var lastTouchY = 0f
    private var lastTouchX = 0f
    private var isExitGestureInProgress = false
    private val GESTURE_THRESHOLD = 100f
    
    private var initialTouchX = 0f
    
    private var voiceAnimationView: View? = null
    private var voiceSearchHandler = Handler(Looper.getMainLooper())
    private var isVoiceSearchActive = false
    private val LONG_PRESS_TIMEOUT = 2000L // 2秒长按阈值
    
    // 添加缩略图缓存相关变量
    private val thumbnailCache = mutableMapOf<Int, android.graphics.Bitmap>()
    private val thumbnailViews = mutableMapOf<Int, ImageView>()
    
    private var initialX = 0
    private var initialY = 0
    
    private lateinit var settingsManager: SettingsManager
    
    // 添加缓存相关变量
    private var cachedCardViews = mutableListOf<View>()
    private var hasInitializedCards = false
    
    private var memoryCheckHandler: Handler? = null
    private var lastMemoryUsage: Long = 0
    private var startTime: Long = 0
    
    private lateinit var gestureDetector: GestureDetector
    
    private var screenshotModeStartTime: Long = 0
    
    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenshotActivity.ACTION_SCREENSHOT_COMPLETED) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - screenshotModeStartTime < 1000) {
                    // 如果间隔过短，忽略该广播事件
                    Log.d("FloatingService", "截图完成广播间隔过短，忽略")
                    return
                }
                
                Log.d("FloatingService", "收到截图完成广播")
                // 立即在主线程上执行恢复
                Handler(Looper.getMainLooper()).post {
                    Log.d("FloatingService", "开始恢复悬浮球状态")
                    restoreFloatingBall()
                }
            }
        }
    }
    
    private var isScreenshotMode = false  // 添加标记位
    
    private val themeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.aifloatingball.LAYOUT_THEME_CHANGED") {
                updateFloatingBallTheme()
            }
        }
    }
    
    private var edgeSnapAnimator: ValueAnimator? = null
    private val edgeSnapThresholdPx by lazy { EDGE_SNAP_THRESHOLD * resources.displayMetrics.density }
    
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        // 当剪贴板内容变化时，立即在主线程上处理
        Handler(Looper.getMainLooper()).post {
            try {
                val clipData = clipboardManager.primaryClip
                val clipText = clipData?.getItemAt(0)?.text?.toString()?.trim()
                
                if (!clipText.isNullOrEmpty()) {
                    Log.d("FloatingService", "检测到新的剪贴板内容: $clipText")
                    showOverlayDialog(clipText)
                }
            } catch (e: Exception) {
                Log.e("FloatingService", "处理剪贴板内容变化失败", e)
            }
        }
    }
    
    // 修改AI搜索引擎配置，确保有明确的logo
    private var menuItemsList: List<MenuItem> = emptyList()
    private val menuViews = mutableListOf<View>()
    
    data class AIEngine(
        val name: String,
        val iconRes: Int,
        val url: String
    )
    
    private var isMenuVisible = false
    private var menuContainer: View? = null
    private val MENU_ITEM_SIZE_DP = 56 // 增大菜单项尺寸
    private val MENU_SPACING_DP = 8 // 菜单项间距
    private val MENU_MARGIN_DP = 16 // 菜单与悬浮球的距离

    private val menuParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private var webViewContainer: View? = null
    private var webView: WebView? = null
    private var isWebViewVisible = false
    private val WEBVIEW_WIDTH_DP = 300  // 悬浮窗宽度
    private val WEBVIEW_HEIGHT_DP = 500 // 悬浮窗高度

    private val menuUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.aifloatingball.ACTION_UPDATE_MENU") {
                // 重新加载菜单项
                menuItemsList = settingsManager.getMenuItems()
                // 重新创建菜单
                recreateMenuContainer()
            }
        }
    }

    // 添加配置变更监听器
    private val configurationChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                // 处理配置变更（如屏幕旋转或折叠屏状态变化）
                handleConfigurationChange()
            }
        }
    }
    
    // 边缘位置枚举
    private enum class EdgePosition {
        LEFT,           // 左边缘
        RIGHT,          // 右边缘
        TOP,            // 上边缘
        BOTTOM,         // 下边缘
        TOP_LEFT,       // 左上角
        TOP_RIGHT,      // 右上角
        BOTTOM_LEFT,    // 左下角
        BOTTOM_RIGHT    // 右下角
    }

    // 添加HalfCircleFloatingWindow变量，但暂时不初始化
    private var halfCircleWindow: HalfCircleFloatingWindow? = null
    // 折叠屏设备检测标志
    private var isFoldableDevice = false

    private var isAISearchMode = true
    private var currentSearchEngines = mutableListOf<MenuItem>()

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        try {
            startTime = System.currentTimeMillis()
            setupMemoryMonitoring()
            Log.d("FloatingService", "服务开始创建")
            super.onCreate()

            // 立即启动前台服务
            startForegroundOrNormal()
            
            // 初始化图标加载器
            iconLoader = IconLoader(this)
            iconLoader.cleanupOldCache()
            
            // 初始化剪贴板管理器并立即开始监听
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            
            // 检查必要的权限
            if (!Settings.canDrawOverlays(this)) {
                Log.e("FloatingService", "没有悬浮窗权限")
                Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 初始化窗口管理器
            try {
                initWindowManager()
            } catch (e: Exception) {
                Log.e("FloatingService", "初始化窗口管理器失败", e)
                Toast.makeText(this, "初始化窗口管理器失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 获取屏幕尺寸
            try {
                initScreenSize()
            } catch (e: Exception) {
                Log.e("FloatingService", "获取屏幕尺寸失败", e)
                Toast.makeText(this, "获取屏幕尺寸失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 初始化所有管理器
            try {
                initManagers()
            } catch (e: Exception) {
                Log.e("FloatingService", "初始化管理器失败", e)
                Toast.makeText(this, "初始化管理器失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 加载保存的菜单项
            menuItemsList = settingsManager.getMenuItems()
            if (menuItemsList.isEmpty()) {
                menuItemsList = getDefaultMenuItems()
            }
            
            // 创建菜单容器
            createMenuContainer()
            
            // 创建并显示悬浮球
            try {
                createFloatingBall()
            } catch (e: Exception) {
                Log.e("FloatingService", "创建悬浮球失败", e)
                Toast.makeText(this, "创建悬浮球失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 注册广播接收器
            registerReceivers()
            
            Log.d("FloatingService", "服务创建完成")
        } catch (e: Exception) {
            Log.e("FloatingService", "服务创建失败", e)
            Toast.makeText(this, "服务创建失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startForegroundOrNormal() {
        try {
            val channelId = "floating_ball_service"
            val channelName = "悬浮球服务"
            
            // 创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持悬浮球服务运行"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            }
                
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, PermissionActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
                
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("悬浮球服务")
                .setContentText("点击管理悬浮球")
                .setSmallIcon(R.drawable.ic_floating_ball)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            // 立即启动前台服务
            startForeground(NOTIFICATION_ID, notification)
            Log.d("FloatingService", "前台服务启动成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "前台服务启动失败", e)
            Toast.makeText(this, "前台服务启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_BALL_SIZE" -> {
                val size = intent.getIntExtra("size", 50)
                updateFloatingBallSize(size)
            }
            "UPDATE_MENU_LAYOUT" -> {
                val layout = intent.getStringExtra("layout") ?: "mixed"
                updateMenuLayout(layout)
            }
            else -> {
                // 创建悬浮球
                if (floatingBallView == null) {
                    createFloatingBall()
                }
            }
        }
        return START_STICKY
    }
    
    private fun updateFloatingBallSize(size: Int) {
        val dpSize = (size * resources.displayMetrics.density).toInt()
        floatingBallView?.let { view ->
            // 更新根布局参数
            val params = view.layoutParams as WindowManager.LayoutParams
            params.width = dpSize
            params.height = dpSize
            
            // 获取悬浮球图标
            val icon = view.findViewById<ImageView>(R.id.floating_ball_icon)
            
            // 更新图标布局参数
            val iconParams = icon.layoutParams
            iconParams.width = dpSize
            iconParams.height = dpSize
            icon.layoutParams = iconParams
            
            // 计算图标的内边距（保持比例）
            val iconPadding = (dpSize * 0.167).toInt() // 保持原有的8dp/48dp的比例
            icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            
            // 确保没有阴影和轮廓
            icon.elevation = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                icon.outlineProvider = null
            }
            
            try {
                // 更新窗口布局
                windowManager?.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e("FloatingService", "更新悬浮球大小失败", e)
            }
        }
    }
    
    private fun createFloatingBall() {
        try {
            floatingBallView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)
            
            // 创建搜索框视图
            searchBoxView = LayoutInflater.from(this).inflate(R.layout.search_box, null)
            
            // 设置搜索框的布局参数
            val searchBoxParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            
            // 设置搜索框的初始位置
            searchBoxParams.gravity = Gravity.TOP or Gravity.START
            searchBoxParams.x = screenWidth / 4
            searchBoxParams.y = screenHeight / 4

            // 设置搜索框的输入事件处理
            val searchEditText = searchBoxView?.findViewById<EditText>(R.id.search_edit_text)
            searchEditText?.setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = v.text.toString()
                    if (query.isNotEmpty()) {
                        performSearch(query)
                    }
                    hideSearchBox()
                    true
                } else {
                    false
                }
            }
            
            // 设置搜索框外部点击事件，用于隐藏搜索框
            searchBoxView?.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val x = event.x
                    val y = event.y
                    if (x < 0 || x > v.width || y < 0 || y > v.height) {
                        hideSearchBox()
                        return@setOnTouchListener true
                    }
                }
                false
            }

            // 设置悬浮球布局参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            // 设置初始位置
            params.gravity = Gravity.TOP or Gravity.START
            params.x = screenWidth - 100
            params.y = screenHeight / 2
            
            // 添加触摸事件监听
            var isLongPress = false
            var longPressHandler = Handler(Looper.getMainLooper())
            var longPressRunnable: Runnable? = null
            var isDragging = false
            
            floatingBallView?.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        isLongPress = false
                        isDragging = false
                        
                        // 设置长按检测
                        longPressRunnable = Runnable {
                            isLongPress = true
                            vibrate(200)
                            showSearchBox()
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, 500)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        
                        // 如果移动距离超过阈值，取消长按并开始拖动
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            longPressHandler.removeCallbacks(longPressRunnable!!)
                            isDragging = true
                        }
                        
                        if (isDragging) {
                            // 计算新位置
                            params.x = (initialX + deltaX).toInt()
                            params.y = (initialY + deltaY).toInt()
                            
                            // 获取悬浮球的宽高
                            val ballWidth = view.width
                            val ballHeight = view.height
                            
                            // 确保悬浮球至少有一部分保持在屏幕内
                            val marginX = ballWidth / 4
                            val marginY = ballHeight / 4
                            
                            params.x = params.x.coerceIn(-ballWidth + marginX, screenWidth - marginX)
                            params.y = params.y.coerceIn(-ballHeight + marginY, screenHeight - marginY)
                            
                            try {
                                windowManager?.updateViewLayout(floatingBallView, params)
                            } catch (e: Exception) {
                                Log.e("FloatingService", "更新悬浮球位置失败", e)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        
                        if (!isLongPress && !isDragging) {
                            if (abs(event.rawX - initialTouchX) < 5 && abs(event.rawY - initialTouchY) < 5) {
                                // 修改这里：单击显示搜索框而不是AI菜单
                                showSearchBox()
                                vibrate(50)
                            }
                        } else if (isDragging) {
                            // 处理拖动结束后的边缘吸附
                            val velocityX = event.rawX - lastTouchX
                            val velocityY = event.rawY - lastTouchY
                            onDragEnd(event.rawX, event.rawY, velocityX, velocityY)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        true
                    }
                    else -> false
                }
            }
            
            // 添加到窗口
            windowManager?.addView(floatingBallView, params)
            windowManager?.addView(searchBoxView, searchBoxParams)
            searchBoxView?.visibility = View.GONE
            
            // 初始化时应用主题
            updateFloatingBallTheme()
            
        } catch (e: Exception) {
            Log.e("FloatingService", "创建悬浮球失败", e)
            Toast.makeText(this, "创建悬浮球失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
    
    private fun showSearchBox() {
        if (!isSearchBoxVisible) {
            // 更新搜索框参数，使其可以获取焦点
            val params = searchBoxView?.layoutParams as? WindowManager.LayoutParams
            params?.flags = params?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) ?: 0
            searchBoxView?.let { windowManager?.updateViewLayout(it, params) }
            
            searchBoxView?.visibility = View.VISIBLE
            isSearchBoxVisible = true
            
            // 初始化搜索引擎列表
            updateSearchEnginesList()
            
            // 设置切换按钮点击事件
            searchBoxView?.findViewById<ImageView>(R.id.toggle_search_mode)?.setOnClickListener {
                isAISearchMode = !isAISearchMode
                updateSearchMode()
                vibrate(50)
            }
            
            // 获取搜索框的EditText和剪贴板提示视图
            val searchEditText = searchBoxView?.findViewById<EditText>(R.id.search_edit_text)
            val clipboardSuggestion = searchBoxView?.findViewById<TextView>(R.id.clipboard_suggestion)
            
            // 获取剪贴板内容
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = if (clipboardManager.hasPrimaryClip()) {
                clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            } else null
            
            // 如果剪贴板有内容，显示提示
            if (!clipText.isNullOrEmpty()) {
                clipboardSuggestion?.apply {
                    text = "粘贴: $clipText"
                    visibility = View.VISIBLE
                    setOnClickListener {
                        searchEditText?.setText(clipText)
                        searchEditText?.setSelection(clipText.length)
                        visibility = View.GONE
                        vibrate(50) // 添加触感反馈
                    }
                }
            } else {
                clipboardSuggestion?.visibility = View.GONE
            }
            
            searchEditText?.let { editText ->
                // 清空之前的输入
                editText.setText("")
                editText.requestFocus()
                
                // 设置文本变化监听
                editText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        // 当用户开始输入时，隐藏剪贴板提示
                        if (s?.isNotEmpty() == true) {
                            clipboardSuggestion?.visibility = View.GONE
                        }
                        // 当输入框有内容时显示搜索引擎列表，否则隐藏
                        searchBoxView?.findViewById<HorizontalScrollView>(R.id.search_engines_scroll)?.visibility =
                            if (s?.isNotEmpty() == true) View.VISIBLE else View.GONE
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
                
                // 设置搜索动作监听
                editText.setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        val query = v.text.toString()
                        if (query.isNotEmpty()) {
                            // 使用默认搜索引擎执行搜索
                            performSearchWithDefaultEngine(query)
                        }
                        hideSearchBox()
                        true
                    } else {
                        false
                    }
                }
                
                // 延迟100毫秒后显示软键盘，确保视图已完全显示
                Handler(Looper.getMainLooper()).postDelayed({
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
                }, 100)
            }
            
            // 初始化搜索模式
            updateSearchMode()
        }
    }
    
    private fun updateSearchMode() {
        val toggleButton = searchBoxView?.findViewById<ImageView>(R.id.toggle_search_mode)
        toggleButton?.setImageResource(
            if (isAISearchMode) R.drawable.ic_ai_search else R.drawable.ic_normal_search
        )
        
        // 更新搜索引擎列表
        updateSearchEnginesList()
    }
    
    private fun updateSearchEnginesList() {
        val container = searchBoxView?.findViewById<LinearLayout>(R.id.search_engines_container)
        container?.removeAllViews()
        
        // 创建GridLayout
        val gridLayout = GridLayout(this).apply {
            columnCount = 6
            useDefaultMargins = true
        }
        
        // 根据当前模式获取搜索引擎列表
        currentSearchEngines = menuItemsList
            .filter { 
                when {
                    isAISearchMode -> it.category == MenuCategory.AI_SEARCH
                    else -> it.category == MenuCategory.NORMAL_SEARCH
                }
            }
            .filter { it.isEnabled }
            .toMutableList()
            
        // 创建并添加搜索引擎图标
        currentSearchEngines.forEach { menuItem ->
            val engineView = LayoutInflater.from(this)
                .inflate(R.layout.search_engine_item, null, false) as ImageView
            
            // 设置图标大小和边距
            val size = resources.getDimensionPixelSize(R.dimen.search_engine_icon_size)
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(8, 8, 8, 8)
            }
            engineView.layoutParams = params
            
            // 加载图标
            if ((menuItem.category == MenuCategory.NORMAL_SEARCH || 
                 menuItem.category == MenuCategory.AI_SEARCH) &&
                !menuItem.url.startsWith("action://")) {
                iconLoader.loadIcon(menuItem.url, engineView, menuItem.iconResId)
            } else {
                engineView.setImageResource(menuItem.iconResId)
            }
            
            // 设置点击事件
            engineView.setOnClickListener {
                val searchEditText = searchBoxView?.findViewById<EditText>(R.id.search_edit_text)
                val query = searchEditText?.text?.toString()
                if (!query.isNullOrEmpty()) {
                    performSearch(query, menuItem)
                    hideSearchBox()
                }
                vibrate(50)
            }
            
            gridLayout.addView(engineView)
        }
        
        container?.addView(gridLayout)
        
        // 根据是否有输入内容决定是否显示搜索引擎列表
        val searchEditText = searchBoxView?.findViewById<EditText>(R.id.search_edit_text)
        searchBoxView?.findViewById<HorizontalScrollView>(R.id.search_engines_scroll)?.visibility =
            if (searchEditText?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }
    
    private fun performSearchWithDefaultEngine(query: String) {
        // 获取默认搜索引擎
        val defaultEngine = currentSearchEngines.firstOrNull()
        if (defaultEngine != null) {
            performSearch(query, defaultEngine)
        }
    }

    private fun hideSearchBox() {
        if (isSearchBoxVisible) {
            // 更新搜索框参数，使其不可获取焦点
            val params = searchBoxView?.layoutParams as? WindowManager.LayoutParams
            params?.flags = params?.flags?.or(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) ?: 0
            searchBoxView?.let { windowManager?.updateViewLayout(it, params) }
            
            searchBoxView?.visibility = View.GONE
            isSearchBoxVisible = false
            
            // 隐藏软键盘
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            searchBoxView?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
    }

    private fun performSearch(query: String) {
        try {
            // 获取默认搜索引擎
            val defaultSearchEngine = menuItemsList
                .filter { it.category == MenuCategory.NORMAL_SEARCH && it.isEnabled }
                .firstOrNull()
                
            if (defaultSearchEngine != null) {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = when (defaultSearchEngine.name) {
                    "百度" -> "https://www.baidu.com/s?wd=$encodedQuery"
                    "Google" -> "https://www.google.com/search?q=$encodedQuery"
                    "必应" -> "https://www.bing.com/search?q=$encodedQuery"
                    "搜狗" -> "https://www.sogou.com/web?query=$encodedQuery"
                    "360搜索" -> "https://www.so.com/s?q=$encodedQuery"
                    else -> "${defaultSearchEngine.url}/search?q=$encodedQuery"
                }
                
                // 使用FloatingWebViewService加载搜索结果
                val intent = Intent(this, FloatingWebViewService::class.java).apply {
                    putExtra("url", searchUrl)
                    putExtra("search_query", query)
                }
                startService(intent)
                
                // 提供反馈
                vibrate(50)
                Toast.makeText(this, "正在搜索: $query", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未设置默认搜索引擎", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "执行搜索失败", e)
            Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startScreenshotMode(view: View) {
        if (isScreenshotMode) {
            Log.d("FloatingService", "已经在截图模式中，忽略请求")
            return
        }
        
        Log.d("FloatingService", "开始截图模式")
        
        try {
            // 保存原始状态
            val originalState = Bundle().apply {
                putFloat("originalAlpha", view.alpha)
                putFloat("originalScaleX", view.scaleX)
                putFloat("originalScaleY", view.scaleY)
                putInt("originalColor", (view.background as? GradientDrawable)?.color?.defaultColor 
                    ?: android.graphics.Color.parseColor("#2196F3"))
            }
            view.tag = originalState
            isScreenshotMode = true
            screenshotModeStartTime = System.currentTimeMillis()

            // 改变悬浮球外观为红色
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.RED)
            }
            view.background = shape
            view.alpha = 0.5f
            view.scaleX = 1.5f
            view.scaleY = 1.5f

            // 启动系统截图
            val intent = Intent(this, ScreenshotActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("REQUEST_SCREENSHOT", REQUEST_SCREENSHOT)
            }
            startActivity(intent)
            
            // 启动延迟任务，如果2秒内没有收到广播，主动恢复悬浮球状态
            Handler(Looper.getMainLooper()).postDelayed({
                if (isScreenshotMode) {
                    Log.d("FloatingService", "截图完成广播超时，主动恢复悬浮球状态")
                    restoreFloatingBall()
                }
            }, 2000)
            
            // 直接返回，不再执行后面的代码
            return
        } catch (e: Exception) {
            Log.e("FloatingService", "启动截图模式失败", e)
            isScreenshotMode = false
            view.tag = null
            restoreFloatingBall()
        }
    }
    
    private fun restoreFloatingBall() {
        if (!isScreenshotMode) {
            Log.d("FloatingService", "当前不在截图模式，忽略恢复请求")
            return
        }
        
        Log.d("FloatingService", "开始恢复悬浮球状态")
        
        floatingBallView?.let { view ->
            try {
                val originalState = view.tag as? Bundle
                if (originalState == null) {
                    Log.e("FloatingService", "找不到原始状态，使用默认值")
                    // 使用默认值恢复
                    val defaultShape = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor("#2196F3"))
                    }
                    view.background = defaultShape
                    view.alpha = 1.0f
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    isScreenshotMode = false
                    view.tag = null
                } else {
                    // 先恢复颜色
                    val shape = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(originalState.getInt("originalColor"))
                    }
                    view.background = shape

                    // 渐变动画恢复
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 300
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { animator ->
                            val fraction = animator.animatedFraction
                            view.scaleX = 1.5f + (originalState.getFloat("originalScaleX") - 1.5f) * fraction
                            view.scaleY = 1.5f + (originalState.getFloat("originalScaleY") - 1.5f) * fraction
                            view.alpha = 0.5f + (originalState.getFloat("originalAlpha") - 0.5f) * fraction
                        }
                        addListener(object : Animator.AnimatorListener {
                            override fun onAnimationStart(animation: Animator) {}
                            override fun onAnimationEnd(animation: Animator) {
                                // 确保最终状态完全正确
                                view.scaleX = originalState.getFloat("originalScaleX")
                                view.scaleY = originalState.getFloat("originalScaleY")
                                view.alpha = originalState.getFloat("originalAlpha")
                                isScreenshotMode = false
                                view.tag = null
                                Log.d("FloatingService", "动画完成，状态已重置，isScreenshotMode: $isScreenshotMode")
                            }
                            override fun onAnimationCancel(animation: Animator) {
                                // 确保在动画取消时也重置状态
                                view.scaleX = originalState.getFloat("originalScaleX")
                                view.scaleY = originalState.getFloat("originalScaleY")
                                view.alpha = originalState.getFloat("originalAlpha")
                                isScreenshotMode = false
                                view.tag = null
                                Log.d("FloatingService", "动画取消，状态已重置，isScreenshotMode: $isScreenshotMode")
                            }
                            override fun onAnimationRepeat(animation: Animator) {}
                        })
                        start()
                    }
                }

                Log.d("FloatingService", "悬浮球状态开始恢复（动画）")
            } catch (e: Exception) {
                Log.e("FloatingService", "恢复悬浮球状态失败", e)
                // 强制恢复默认状态
                val defaultShape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#2196F3"))
                }
                view.background = defaultShape
                view.alpha = 1.0f
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                isScreenshotMode = false
                view.tag = null
                Log.d("FloatingService", "强制恢复默认状态，isScreenshotMode: $isScreenshotMode")
            }
        }
        
        isScreenshotMode = false
        Log.d("FloatingService", "悬浮球状态恢复完毕，isScreenshotMode: $isScreenshotMode")
    }
    
    // 工具方法
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    private fun Float.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }
    
    private fun showSearchInput() {
        try {
            // 获取剪贴板文本
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipboardText = clipboard.primaryClip?.let { 
                if (it.itemCount > 0) it.getItemAt(0).text?.toString() 
                else null 
            }

            // 启动搜索Activity，并传递剪贴板文本
            val intent = Intent(this, SearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipboardText?.let { 
                    putExtra("CLIPBOARD_TEXT", it) 
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("FloatingService", "启动搜索Activity失败", e)
            Toast.makeText(this, "启动搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupVoiceInput() {
        try {
        // 初始化语音识别器
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                        isVoiceSearchActive = true
                        showVoiceSearchAnimation()
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                
                override fun onError(error: Int) {
                    stopVoiceSearchMode()
                        Toast.makeText(this@FloatingWindowService, "语音识别失败，请重试", Toast.LENGTH_SHORT).show()
                }
                
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val recognizedText = matches[0]
                            showSearchInput()
                    }
                    stopVoiceSearchMode()
                }
                
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "语音识别初始化失败", e)
            Toast.makeText(this, "语音识别初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceSearchMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请授予录音权限以使用语音搜索", Toast.LENGTH_LONG).show()
            return
        }

        try {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
            recognizer?.startListening(intent)
            showVoiceSearchAnimation()
        } catch (e: Exception) {
            Log.e("FloatingService", "启动语音搜索失败", e)
            Toast.makeText(this, "启动语音搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopVoiceSearchMode() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            hideVoiceSearchAnimation()
        isVoiceSearchActive = false
        } catch (e: Exception) {
            Log.e("FloatingService", "停止语音搜索失败", e)
        }
    }

    private fun showVoiceSearchAnimation() {
        // 创建语音动画视图
        if (voiceAnimationView == null) {
            voiceAnimationView = LayoutInflater.from(this).inflate(R.layout.voice_search_animation, null)
        }

        // 设置动画视图的布局参数
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(voiceAnimationView, params)
            
            // 开始动画
            val animation = AnimationUtils.loadAnimation(this, R.anim.voice_ripple)
            voiceAnimationView?.findViewById<View>(R.id.voice_ripple)?.startAnimation(animation)
        } catch (e: Exception) {
            Log.e("FloatingService", "显示语音动画失败", e)
        }
    }

    private fun hideVoiceSearchAnimation() {
        try {
            voiceAnimationView?.let {
                windowManager?.removeView(it)
                voiceAnimationView = null
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "隐藏语音动画失败", e)
        }
    }

    private fun setupMemoryMonitoring() {
        memoryCheckHandler = Handler(Looper.getMainLooper())
        val memoryCheckRunnable = object : Runnable {
            override fun run() {
                checkMemoryUsage()
                memoryCheckHandler?.postDelayed(this, MEMORY_CHECK_INTERVAL)
            }
        }
        memoryCheckHandler?.post(memoryCheckRunnable)
    }

    private fun checkMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // 如果内存使用增加超过阈值，执行清理
        if (usedMemory > lastMemoryUsage * 1.5) {
            System.gc()
            Log.d("MemoryCheck", "执行内存清理")
        }
        
        lastMemoryUsage = usedMemory
    }
    
    override fun onDestroy() {
        try {
            if (::clipboardManager.isInitialized) {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            }
            unregisterReceiver(screenshotReceiver)
            unregisterReceiver(themeReceiver)
            unregisterReceiver(menuUpdateReceiver)
            memoryCheckHandler?.removeCallbacksAndMessages(null)
            recognizer?.destroy()
            
            windowManager?.let { wm ->
                floatingBallView?.let { wm.removeView(it) }
                voiceAnimationView?.let { wm.removeView(it) }
                menuContainer?.let { wm.removeView(it) }
            }
            
            // 清理缓存
            thumbnailCache.clear()
            thumbnailViews.clear()
            cachedCardViews.clear()
            menuViews.clear()
            
            // 清理图标加载器
            if (::iconLoader.isInitialized) {
                iconLoader.clearCache()
            }
            
            System.gc()
            
            // 注销配置变更监听器
            try {
                unregisterReceiver(configurationChangeReceiver)
            } catch (e: Exception) {
                Log.e("FloatingWindowService", "Error unregistering receiver", e)
            }
            
            super.onDestroy()
        } catch (e: Exception) {
            Log.e("FloatingService", "服务销毁时发生错误", e)
        }
    }

    // 实现GestureCallback接口方法
    override fun onGestureDetected(gesture: GestureManager.Gesture) {
        when (gesture) {
            GestureManager.Gesture.SWIPE_UP -> {
                // 移除搜索相关代码
            }
            GestureManager.Gesture.SWIPE_DOWN -> {
                // 移除搜索相关代码
            }
            GestureManager.Gesture.LONG_PRESS -> {
                quickMenuManager.showQuickMenu()
            }
        }
    }

    private fun performClick() {
        // 移除搜索相关代码
    }

    override fun onDoubleTap() {
        // 移除搜索相关代码
    }

    override fun onSingleTap() {
        // 移除搜索相关代码
    }

    override fun onLongPress() {
        quickMenuManager.showQuickMenu()
    }

    override fun onSwipeLeft() {
        // Not used in current implementation
    }

    override fun onSwipeRight() {
        // Not used in current implementation
    }

    override fun onSwipeUp() {
        // 移除搜索相关代码
    }

    override fun onSwipeDown() {
        // 移除搜索相关代码
    }

    override fun onDrag(x: Float, y: Float) {
        floatingBallView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            
            // 限制在屏幕范围内，垂直方向不需要额外处理
            params.x = x.toInt().coerceIn(-view.width / 3, screenWidth - view.width * 2 / 3)
            params.y = y.toInt().coerceIn(0, screenHeight - view.height)
            
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e("FloatingService", "更新悬浮球位置失败", e)
            }
        }
    }

    override fun onDragEnd(x: Float, y: Float, velocityX: Float, velocityY: Float) {
        floatingBallView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            
            // 处理甩动效果
            if (abs(velocityX) > FLING_MIN_VELOCITY || abs(velocityY) > FLING_MIN_VELOCITY) {
                handleFling(params, velocityX, velocityY)
                return
            }
            
            // 获取屏幕方向
            val orientation = resources.configuration.orientation
            
            // 计算悬浮球中心点和各个边缘的距离
            val ballWidth = view.width
            val ballHeight = view.height
            val distanceToLeftEdge = params.x
            val distanceToRightEdge = screenWidth - (params.x + ballWidth)
            val distanceToTopEdge = params.y
            val distanceToBottomEdge = screenHeight - (params.y + ballHeight)
            
            // 是否在各个边缘附近
            val nearLeft = distanceToLeftEdge <= edgeSnapThresholdPx * 1.5f
            val nearRight = distanceToRightEdge <= edgeSnapThresholdPx * 1.5f
            val nearTop = distanceToTopEdge <= edgeSnapThresholdPx * 1.5f
            val nearBottom = distanceToBottomEdge <= edgeSnapThresholdPx * 1.5f
            
            // 根据当前屏幕方向和位置选择适合的边缘
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // 横屏优先考虑左右边缘
            when {
                    nearLeft && nearTop -> snapToEdge(params, EdgePosition.TOP_LEFT)
                    nearLeft && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_LEFT)
                    nearRight && nearTop -> snapToEdge(params, EdgePosition.TOP_RIGHT)
                    nearRight && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_RIGHT)
                    nearLeft -> snapToEdge(params, EdgePosition.LEFT)
                    nearRight -> snapToEdge(params, EdgePosition.RIGHT)
                    nearTop -> snapToEdge(params, EdgePosition.TOP)
                    nearBottom -> snapToEdge(params, EdgePosition.BOTTOM)
                }
            } else {
                // 竖屏也优先考虑左右边缘
                when {
                    nearLeft && nearTop -> snapToEdge(params, EdgePosition.TOP_LEFT)
                    nearLeft && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_LEFT)
                    nearRight && nearTop -> snapToEdge(params, EdgePosition.TOP_RIGHT)
                    nearRight && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_RIGHT)
                    nearLeft -> snapToEdge(params, EdgePosition.LEFT)
                    nearRight -> snapToEdge(params, EdgePosition.RIGHT)
                    nearTop -> snapToEdge(params, EdgePosition.TOP)
                    nearBottom && params.y > screenHeight * 0.6 -> snapToEdge(params, EdgePosition.BOTTOM)
                }
            }
        }
    }
    
    private fun handleFling(params: WindowManager.LayoutParams, velocityX: Float, velocityY: Float) {
        edgeSnapAnimator?.cancel()
        
        val view = floatingBallView ?: return
        val startX = params.x
        val startY = params.y
        
        // 调整减速系数，使甩动更自然
        val deceleration = 0.8f
        val duration = (sqrt((velocityX * velocityX + velocityY * velocityY)) / deceleration).toLong()
        // 水平方向保持原有逻辑
        val distanceX = (velocityX * duration / 2500f).toInt()
        // 垂直方向使用更小的系数，减少移动距离
        val distanceY = (velocityY * duration / 3000f).toInt()
        
        var targetX = (startX + distanceX).coerceIn(-view.width / 3, screenWidth - view.width * 2 / 3)
        // 垂直方向只需要确保不超出屏幕边界
        val targetY = (startY + distanceY).coerceIn(0, screenHeight - view.height)
        
        // 只处理水平方向的边缘吸附
        val snapDistance = edgeSnapThresholdPx * 2
        if (targetX < snapDistance) {
            targetX = -view.width / 3
        } else if (targetX > screenWidth - view.width * 2 / 3 - snapDistance) {
            targetX = screenWidth - view.width * 2 / 3
        }
        
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = min(duration, ANIMATION_DURATION)
            interpolator = DecelerateInterpolator(1.2f)
            
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e("FloatingService", "更新悬浮球位置失败", e)
                }
            }
            
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    // 获取屏幕方向
                    val orientation = resources.configuration.orientation
                    
                    // 计算到各边的距离
                    val ballWidth = view.width
                    val ballHeight = view.height
                    val distanceToLeftEdge = params.x
                    val distanceToRightEdge = screenWidth - (params.x + ballWidth)
                    val distanceToTopEdge = params.y
                    val distanceToBottomEdge = screenHeight - (params.y + ballHeight)
                    
                    // 是否在各个边缘附近
                    val nearLeft = distanceToLeftEdge <= edgeSnapThresholdPx * 1.5f
                    val nearRight = distanceToRightEdge <= edgeSnapThresholdPx * 1.5f
                    val nearTop = distanceToTopEdge <= edgeSnapThresholdPx * 1.5f
                    val nearBottom = distanceToBottomEdge <= edgeSnapThresholdPx * 1.5f
                    
                    // 根据当前屏幕方向智能选择边缘
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        // 横屏优先考虑左右边缘
                    when {
                            nearLeft && nearTop -> snapToEdge(params, EdgePosition.TOP_LEFT)
                            nearLeft && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_LEFT)
                            nearRight && nearTop -> snapToEdge(params, EdgePosition.TOP_RIGHT)
                            nearRight && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_RIGHT)
                            nearLeft -> snapToEdge(params, EdgePosition.LEFT)
                            nearRight -> snapToEdge(params, EdgePosition.RIGHT)
                            nearTop -> snapToEdge(params, EdgePosition.TOP)
                            nearBottom -> snapToEdge(params, EdgePosition.BOTTOM)
                        }
                    } else {
                        // 竖屏也优先考虑左右边缘
                        when {
                            nearLeft && nearTop -> snapToEdge(params, EdgePosition.TOP_LEFT)
                            nearLeft && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_LEFT)
                            nearRight && nearTop -> snapToEdge(params, EdgePosition.TOP_RIGHT)
                            nearRight && nearBottom -> snapToEdge(params, EdgePosition.BOTTOM_RIGHT)
                            nearLeft -> snapToEdge(params, EdgePosition.LEFT)
                            nearRight -> snapToEdge(params, EdgePosition.RIGHT)
                            nearTop -> snapToEdge(params, EdgePosition.TOP)
                            nearBottom && params.y > screenHeight * 0.6 -> snapToEdge(params, EdgePosition.BOTTOM)
                        }
                    }
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            
            start()
        }
    }
    
    private fun snapToEdge(params: WindowManager.LayoutParams, position: EdgePosition) {
        edgeSnapAnimator?.cancel()
        
        val view = floatingBallView ?: return
        val startX = params.x
        val startY = params.y
        var targetX = startX
        var targetY = startY
        
        // 根据不同位置计算目标坐标
        when (position) {
            EdgePosition.LEFT -> {
                targetX = -view.width / 4
                // Y轴保持不变
            }
            EdgePosition.RIGHT -> {
                targetX = screenWidth - view.width * 3 / 4
                // Y轴保持不变
            }
            EdgePosition.TOP -> {
                targetY = 0
                // X轴保持不变
            }
            EdgePosition.BOTTOM -> {
                targetY = screenHeight - view.height
                // X轴保持不变
            }
            EdgePosition.TOP_LEFT -> {
                targetX = -view.width / 4
                targetY = 0
            }
            EdgePosition.TOP_RIGHT -> {
                targetX = screenWidth - view.width * 3 / 4
                targetY = 0
            }
            EdgePosition.BOTTOM_LEFT -> {
                targetX = -view.width / 4
                targetY = screenHeight - view.height
            }
            EdgePosition.BOTTOM_RIGHT -> {
                targetX = screenWidth - view.width * 3 / 4
                targetY = screenHeight - view.height
            }
        }
        
        // 计算移动距离，用于调整动画参数
        val distanceX = abs(targetX - startX)
        val distanceY = abs(targetY - startY)
        val distance = sqrt((distanceX * distanceX + distanceY * distanceY).toDouble()).toFloat()
        
        edgeSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            // 根据距离调整动画时长，距离越长动画越慢
            duration = min(150L + (distance / 3).toLong(), 300L)
            
            // 根据距离选择合适的插值器
            interpolator = if (distance > screenWidth / 6) {
                // 大距离使用减速插值器，更平滑
                DecelerateInterpolator(1.5f)
            } else {
                // 小距离使用弱化的回弹效果
                OvershootInterpolator(0.3f)
            }
            
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                params.x = lerp(startX, targetX, fraction)
                params.y = lerp(startY, targetY, fraction)
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e("FloatingService", "更新悬浮球位置失败", e)
                }
            }
            
            start()
        }
    }
    
    // 为兼容已有代码，保留之前的方法签名
    private fun snapToEdge(params: WindowManager.LayoutParams, toLeft: Boolean) {
        snapToEdge(params, if (toLeft) EdgePosition.LEFT else EdgePosition.RIGHT)
    }
    
    // 线性插值辅助方法
    private fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + fraction * (end - start)).toInt()
    }

    private fun updateFloatingBallTheme() {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        
        floatingBallView?.apply {
            if (isDarkMode) {
                // 暗色主题
                background = ContextCompat.getDrawable(this@FloatingWindowService, R.drawable.bg_floating_ball_dark)
                findViewById<ImageView>(R.id.floating_ball_icon)?.apply {
                    setColorFilter(ContextCompat.getColor(context, R.color.floating_ball_icon_dark))
                }
            } else {
                // 亮色主题
                background = ContextCompat.getDrawable(this@FloatingWindowService, R.drawable.bg_floating_ball_light)
                findViewById<ImageView>(R.id.floating_ball_icon)?.apply {
                    setColorFilter(ContextCompat.getColor(context, R.color.floating_ball_icon_light))
                }
            }
        }
    }

    private fun handleClipboardContent() {
        try {
            Log.d("FloatingService", "开始检查剪贴板内容")
            
            if (!::clipboardManager.isInitialized) {
                clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            }

            if (!clipboardManager.hasPrimaryClip()) {
                Log.d("FloatingService", "剪贴板为空")
                openDefaultSearch()
                return
            }

            val clipData = clipboardManager.primaryClip
            val clipText = clipData?.getItemAt(0)?.text?.toString()?.trim()

            Log.d("FloatingService", "获取到剪贴板内容: $clipText")

            if (clipText.isNullOrEmpty()) {
                Log.d("FloatingService", "剪贴板内容为空")
                openDefaultSearch()
                return
            }

            // 在主线程上创建并显示悬浮对话框
            Handler(Looper.getMainLooper()).post {
                showOverlayDialog(clipText)
            }

        } catch (e: Exception) {
            Log.e("FloatingService", "处理剪贴板内容失败", e)
            openDefaultSearch()
        }
    }

    private fun showOverlayDialog(content: String) {
        try {
            // 创建对话框视图
            val dialogView = LayoutInflater.from(this).inflate(R.layout.overlay_dialog, null)
            
            // 设置窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            // 设置内容
            dialogView.findViewById<TextView>(R.id.dialog_title).text = "检测到剪贴板内容"
            dialogView.findViewById<TextView>(R.id.dialog_message).text = content
            
            // 设置按钮点击事件
            if (URLUtil.isValidUrl(content)) {
                dialogView.findViewById<Button>(R.id.btn_open_link).apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                windowManager?.removeView(dialogView)
                        openUrl(content)
                    }
                }
            }

            dialogView.findViewById<Button>(R.id.btn_search).setOnClickListener {
                windowManager?.removeView(dialogView)
                searchContent(content)
            }

            dialogView.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
                windowManager?.removeView(dialogView)
                // 根据设置决定打开哪个页面
                val defaultPage = settingsManager.getDefaultPage()
                val intent = if (defaultPage == "home") {
                    Intent(this, com.example.aifloatingball.HomeActivity::class.java)
                } else {
                    Intent(this, SearchActivity::class.java)
                }
                intent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(intent)
                // 确保页面正确加载
                Log.d("FloatingService", "取消按钮点击，打开默认页面: $defaultPage")
            }

            // 添加到窗口
            windowManager?.addView(dialogView, params)

            // 设置自动关闭
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (dialogView.parent != null) {
                        windowManager?.removeView(dialogView)
                        openDefaultSearch()
                    }
                } catch (e: Exception) {
                    Log.e("FloatingService", "移除对话框失败", e)
                }
            }, 5000)

        } catch (e: Exception) {
            Log.e("FloatingService", "显示悬浮对话框失败", e)
            openDefaultSearch()
        }
    }

    private fun openUrl(url: String) {
        try {
            Log.d("FloatingService", "准备打开URL: $url")
            val intent = Intent(this, FullscreenWebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // 添加无动画标志，使打开更快
                putExtra("url", url)
        }
        startActivity(intent)
        } catch (e: Exception) {
            Log.e("FloatingService", "打开URL失败", e)
            Toast.makeText(this, "打开URL失败: ${e.message}", Toast.LENGTH_SHORT).show()
            openDefaultSearch()
        }
    }

    private fun searchContent(query: String) {
        try {
            Log.d("FloatingService", "准备搜索内容: $query")
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.baidu.com/s?wd=$encodedQuery"
            openUrl(searchUrl)
        } catch (e: Exception) {
            Log.e("FloatingService", "搜索内容失败", e)
            Toast.makeText(this, "搜索内容失败: ${e.message}", Toast.LENGTH_SHORT).show()
            openDefaultSearch()
        }
    }

    private fun openDefaultSearch() {
        Log.d("FloatingService", "打开默认页面")
        try {
            // 根据设置决定打开哪个页面
            val defaultPage = settingsManager.getDefaultPage()
            val intent = if (defaultPage == "home") {
                // 使用正确的包路径打开HomeActivity
                Intent(this, com.example.aifloatingball.HomeActivity::class.java)
            } else {
                Intent(this, SearchActivity::class.java)
            }
            intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
        } catch (e: Exception) {
            Log.e("FloatingService", "打开默认页面失败", e)
            Toast.makeText(this, "打开默认页面失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 添加震动函数
    private fun vibrate(duration: Long) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            Log.d("FloatingService", "执行震动: $duration ms")
        } catch (e: Exception) {
            Log.e("FloatingService", "震动失败", e)
        }
    }
    
    // 创建菜单容器
    private fun createMenuContainer(): View? {
        try {
            val container = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // 主菜单布局
            val mainMenuLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(16, 16, 16, 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP
                }
            }

            // 搜索框布局
            val searchLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.search_background)
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }

            // 搜索输入框
            val searchEditText = EditText(this).apply {
                id = R.id.search_edit_text
                hint = "搜索..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#80FFFFFF"))
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        val query = text.toString()
                        if (query.isNotEmpty()) {
                            // 获取默认搜索引擎
                            val defaultSearchEngine = menuItemsList
                                .filter { it.category == MenuCategory.NORMAL_SEARCH && it.isEnabled }
                                .firstOrNull()
                            
                            if (defaultSearchEngine != null) {
                                performSearch(query, defaultSearchEngine)
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
            }

            searchLayout.addView(searchEditText)

            // 菜单内容滚动视图
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val menuContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // 添加各个分类的菜单项
            val enabledItems = menuItemsList.filter { it.isEnabled }
            
            // AI助手分类
            val aiItems = enabledItems.filter { it.category == MenuCategory.AI_SEARCH }
            if (aiItems.isNotEmpty()) {
                addMenuSection(menuContent, "AI助手", aiItems)
            }

            // 搜索引擎分类
            val searchItems = enabledItems.filter { it.category == MenuCategory.NORMAL_SEARCH }
            if (searchItems.isNotEmpty()) {
                addMenuSection(menuContent, "搜索引擎", searchItems)
            }

            // 功能分类
            val functionItems = enabledItems.filter { it.category == MenuCategory.FUNCTION }
            if (functionItems.isNotEmpty()) {
                addMenuSection(menuContent, "功能", functionItems)
            }

            scrollView.addView(menuContent)
            mainMenuLayout.addView(searchLayout)
            mainMenuLayout.addView(scrollView)
            container.addView(mainMenuLayout)

            // 点击空白处关闭菜单
            container.setOnClickListener { hideAIMenu() }

            return container
        } catch (e: Exception) {
            Log.e(TAG, "Error creating menu container: ${e.message}")
            return null
        }
    }

    private fun addMenuSection(parent: LinearLayout, title: String, items: List<MenuItem>) {
        if (items.isEmpty()) return

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
        parent.addView(titleView)

        val gridLayout = GridLayout(this).apply {
            columnCount = 4
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        items.forEach { menuItem ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.menu_item, gridLayout, false)
            val icon = itemView.findViewById<ImageView>(R.id.icon)
            val name = itemView.findViewById<TextView>(R.id.name)

            name.text = menuItem.name

            // 如果是搜索引擎，尝试加载网站图标
            if ((menuItem.category == MenuCategory.NORMAL_SEARCH || 
                 menuItem.category == MenuCategory.AI_SEARCH) &&
                !menuItem.url.startsWith("action://")) {
                iconLoader.loadIcon(menuItem.url, icon, menuItem.iconResId)
            } else {
                icon.setImageResource(menuItem.iconResId)
            }

            itemView.setOnClickListener {
                menuItem.action()
                hideAIMenu()
                handleMenuItemClick(menuItem)
            }

            gridLayout.addView(itemView)
        }

        parent.addView(gridLayout)
    }

    private fun handleMenuItemClick(menuItem: MenuItem) {
        try {
            Log.d(TAG, "处理菜单项点击: ${menuItem.name}")
            
            when {
                menuItem.url == "back://last_app" -> {
                    handleBackToLastApp()
                }
                menuItem.url == "action://screenshot" -> {
                    startScreenshotMode(floatingBallView!!)
                }
                menuItem.url == "action://settings" -> {
                    val intent = Intent(this, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                menuItem.url == "action://share" -> {
                    handleShare()
                }
                // 检查是否是普通搜索引擎
                menuItem.category == MenuCategory.NORMAL_SEARCH -> {
                    handleNormalSearch(menuItem)
                }
                else -> {
                    // 打开对应的URL
                    val intent = Intent(this, FloatingWebViewService::class.java).apply {
                        putExtra("url", menuItem.url)
                        putExtra("from_ai_menu", true)
                    }
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理菜单项点击失败", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSearch(query: String, searchEngine: MenuItem) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = when (searchEngine.name) {
                "百度" -> "https://www.baidu.com/s?wd=$encodedQuery"
                "Google" -> "https://www.google.com/search?q=$encodedQuery"
                "必应" -> "https://www.bing.com/search?q=$encodedQuery"
                "搜狗" -> "https://www.sogou.com/web?query=$encodedQuery"
                "360搜索" -> "https://www.so.com/s?q=$encodedQuery"
                else -> "${searchEngine.url}/search?q=$encodedQuery"
            }

            // 使用悬浮窗打开搜索结果
            val intent = Intent(this, FloatingWebViewService::class.java).apply {
                putExtra("url", searchUrl)
                putExtra("from_ai_menu", true)
                putExtra("search_query", query)
            }
            startService(intent)
            
            // 提供反馈
            vibrate(50)
            Toast.makeText(this, "正在搜索: $query", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "执行搜索失败", e)
            Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNormalSearch(menuItem: MenuItem) {
        try {
            Log.d("FloatingService", "开始处理普通搜索: ${menuItem.name}")
            
            // 获取剪贴板内容
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            
            Log.d("FloatingService", "剪贴板内容: $clipText")

            if (!clipText.isNullOrEmpty()) {
                // 对搜索词进行 URL 编码
                val encodedQuery = java.net.URLEncoder.encode(clipText, "UTF-8")
                Log.d("FloatingService", "编码后的搜索词: $encodedQuery")
                
                // 构建搜索URL，确保使用正确的搜索参数
                val searchUrl = when (menuItem.name) {
                    "百度" -> "https://www.baidu.com/s?wd=$encodedQuery"
                    "Google" -> "https://www.google.com/search?q=$encodedQuery"
                    "必应" -> "https://www.bing.com/search?q=$encodedQuery"
                    "搜狗" -> "https://www.sogou.com/web?query=$encodedQuery"
                    "360搜索" -> "https://www.so.com/s?q=$encodedQuery"
                    "头条搜索" -> "https://so.toutiao.com/search?keyword=$encodedQuery"
                    "夸克搜索" -> "https://quark.sm.cn/s?q=$encodedQuery"
                    "神马搜索" -> "https://m.sm.cn/s?q=$encodedQuery"
                    "Yandex" -> "https://yandex.com/search/?text=$encodedQuery"
                    "DuckDuckGo" -> "https://duckduckgo.com/?q=$encodedQuery"
                    "Yahoo" -> "https://search.yahoo.com/search?p=$encodedQuery"
                    "Ecosia" -> "https://www.ecosia.org/search?q=$encodedQuery"
                    else -> "${menuItem.url}/search?q=$encodedQuery"
                }
                
                Log.d("FloatingService", "构建的搜索URL: $searchUrl")

                // 使用 WebView 打开搜索结果页面
                val intent = Intent(this, FloatingWebViewService::class.java).apply {
                    putExtra("url", searchUrl)
                    putExtra("from_ai_menu", true)
                    putExtra("search_query", clipText) // 添加搜索词，方便调试
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                // 启动服务
                startService(intent)
                
                // 提供反馈
                vibrate(50)
                Toast.makeText(this, "正在搜索: $clipText", Toast.LENGTH_SHORT).show()
                
                Log.d("FloatingService", "搜索服务已启动")
            } else {
                Log.d("FloatingService", "剪贴板为空，打开搜索主页")
                
                // 如果没有剪贴板内容，打开搜索引擎主页
                val intent = Intent(this, FloatingWebViewService::class.java).apply {
                    putExtra("url", menuItem.url)
                    putExtra("from_ai_menu", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "处理普通搜索失败", e)
            e.printStackTrace() // 打印详细错误堆栈
            Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
            
            // 发生错误时打开搜索引擎主页
            val intent = Intent(this, FloatingWebViewService::class.java).apply {
                putExtra("url", menuItem.url)
                putExtra("from_ai_menu", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startService(intent)
        }
    }

    private fun handleShare() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "分享自AI悬浮球")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(intent, "分享到").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // 添加处理返回功能的方法
    private fun handleBackToLastApp() {
        try {
            val lastApp = getForegroundAppPackage()
            if (lastApp != null && lastApp != packageName) {
                val intent = packageManager.getLaunchIntentForPackage(lastApp)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(intent)
                    
                    // 使用振动反馈
                    vibrate(50) // 使用较短的振动时间，模拟轻触反馈
                    
                    // 显示提示
                    val appName = getAppName(lastApp)
                    Toast.makeText(this, "返回到: $appName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "无法返回到上一个应用", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "没有上一个应用记录", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "返回上一个应用失败", e)
            Toast.makeText(this, "返回失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 添加获取应用名称的方法
    private fun getAppName(packageName: String): String {
        try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            return packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            return packageName
        }
    }

    // 添加获取前台应用的方法
    private fun getForegroundAppPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    time - 1000 * 60,
                    time
                )
                
                return stats
                    ?.filter { it.packageName != packageName } // 排除自己
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.packageName
            } catch (e: Exception) {
                Log.e("FloatingService", "获取前台应用失败", e)
            }
        }
        return null
    }

    private fun getDefaultMenuItems(): List<MenuItem> {
        return listOf(
            // AI 搜索引擎
            MenuItem("ChatGPT", R.drawable.ic_chatgpt, "https://chat.openai.com", MenuCategory.AI_SEARCH, true),
            MenuItem("Claude", R.drawable.ic_claude, "https://claude.ai", MenuCategory.AI_SEARCH, true),
            MenuItem("文心一言", R.drawable.ic_wenxin, "https://yiyan.baidu.com", MenuCategory.AI_SEARCH, true),
            MenuItem("通义千问", R.drawable.ic_qianwen, "https://qianwen.aliyun.com", MenuCategory.AI_SEARCH, true),
            MenuItem("讯飞星火", R.drawable.ic_xinghuo, "https://xinghuo.xfyun.cn", MenuCategory.AI_SEARCH, true),
            MenuItem("Gemini", R.drawable.ic_gemini, "https://gemini.google.com", MenuCategory.AI_SEARCH, true),
            MenuItem("DeepSeek", R.drawable.ic_deepseek, "https://chat.deepseek.com", MenuCategory.AI_SEARCH, true),
            MenuItem("智谱清言", R.drawable.ic_zhipu, "https://chatglm.cn", MenuCategory.AI_SEARCH, true),
            MenuItem("Kimi", R.drawable.ic_kimi, "https://kimi.moonshot.cn", MenuCategory.AI_SEARCH, true),
            MenuItem("百小应", R.drawable.ic_floating_ball, "https://yiyan.baidu.com/welcome", MenuCategory.AI_SEARCH, true),
            MenuItem("海螺", R.drawable.ic_floating_ball, "https://chat.baichuan-ai.com", MenuCategory.AI_SEARCH, true),
            MenuItem("豆包", R.drawable.ic_floating_ball, "https://www.doubao.com", MenuCategory.AI_SEARCH, true),
            MenuItem("腾讯元宝", R.drawable.ic_floating_ball, "https://hunyuan.tencent.com", MenuCategory.AI_SEARCH, true),
            MenuItem("秘塔AI", R.drawable.ic_floating_ball, "https://meta-llama.ai", MenuCategory.AI_SEARCH, true),
            MenuItem("Poe", R.drawable.ic_floating_ball, "https://poe.com", MenuCategory.AI_SEARCH, true),
            MenuItem("Perplexity", R.drawable.ic_perplexity, "https://perplexity.ai", MenuCategory.AI_SEARCH, true),
            MenuItem("天工AI", R.drawable.ic_floating_ball, "https://tiangong.cn", MenuCategory.AI_SEARCH, true),
            MenuItem("Grok", R.drawable.ic_grok, "https://grok.x.ai", MenuCategory.AI_SEARCH, true),
            MenuItem("小艺", R.drawable.ic_floating_ball, "https://yiwise.com/xiaoyi", MenuCategory.AI_SEARCH, true),
            MenuItem("Monica", R.drawable.ic_floating_ball, "https://monica.im", MenuCategory.AI_SEARCH, true),
            MenuItem("You", R.drawable.ic_floating_ball, "https://you.com", MenuCategory.AI_SEARCH, true),
            MenuItem("纳米AI", R.drawable.ic_floating_ball, "https://nanoai.com", MenuCategory.AI_SEARCH, true),
            MenuItem("Copilot", R.drawable.ic_floating_ball, "https://copilot.microsoft.com", MenuCategory.AI_SEARCH, true),
            MenuItem("Anthropic", R.drawable.ic_floating_ball, "https://anthropic.com", MenuCategory.AI_SEARCH, true),
            MenuItem("Character", R.drawable.ic_floating_ball, "https://character.ai", MenuCategory.AI_SEARCH, true),
            MenuItem("Pi", R.drawable.ic_floating_ball, "https://pi.ai", MenuCategory.AI_SEARCH, true),
            
            // 普通搜索引擎
            MenuItem("百度", R.drawable.ic_baidu, "https://www.baidu.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("Google", R.drawable.ic_google, "https://www.google.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("必应", R.drawable.ic_bing, "https://www.bing.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("搜狗", R.drawable.ic_sogou, "https://www.sogou.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("360搜索", R.drawable.ic_360, "https://www.so.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("头条搜索", R.drawable.ic_floating_ball, "https://so.toutiao.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("夸克搜索", R.drawable.ic_floating_ball, "https://quark.sm.cn", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("神马搜索", R.drawable.ic_floating_ball, "https://m.sm.cn", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("Yandex", R.drawable.ic_floating_ball, "https://yandex.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("DuckDuckGo", R.drawable.ic_floating_ball, "https://duckduckgo.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("Yahoo", R.drawable.ic_floating_ball, "https://search.yahoo.com", MenuCategory.NORMAL_SEARCH, true),
            MenuItem("Ecosia", R.drawable.ic_floating_ball, "https://www.ecosia.org", MenuCategory.NORMAL_SEARCH, true),
            
            // 功能
            MenuItem("返回", R.drawable.ic_back, "back://last_app", MenuCategory.FUNCTION, true),
            MenuItem("截图", R.drawable.ic_screenshot, "action://screenshot", MenuCategory.FUNCTION, true),
            MenuItem("设置", R.drawable.ic_settings, "action://settings", MenuCategory.FUNCTION, true),
            MenuItem("分享", R.drawable.ic_share, "action://share", MenuCategory.FUNCTION, true)
        )
    }

    private fun recreateMenuContainer() {
        // 移除旧的菜单容器
        menuContainer?.let {
            windowManager?.removeView(it)
            menuContainer = null
        }
        menuViews.clear()
        
        // 创建新的菜单容器
        createMenuContainer()
    }
    
    // 处理配置变更
    private fun handleConfigurationChange() {
        try {
            // 更新屏幕尺寸
            updateScreenDimensions()
            
            // 确保悬浮球在屏幕范围内
            adjustFloatingBallPosition()
            
            // 如果有展开的菜单，重新定位菜单
            if (menuContainer != null && menuContainer?.visibility == View.VISIBLE) {
                repositionMenu()
            }
            
            // 暂时禁用半圆窗口功能，因为HalfCircleFloatingWindow类中的属性无法访问
            // 当HalfCircleFloatingWindow类添加公开方法后可以重新启用
            // if (halfCircleWindow != null && halfCircleWindow?.isHidden == true) {
            //     updateEdgePosition()
            // }
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "Error handling configuration change", e)
        }
    }
    
    // 更新屏幕尺寸
    private fun updateScreenDimensions() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                screenWidth = windowMetrics.bounds.width()
                screenHeight = windowMetrics.bounds.height()
            } else {
                val display = windowManager.defaultDisplay
                val metrics = DisplayMetrics()
                display.getMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
            
            // 检测折叠屏状态
            val isFoldable = checkFoldableDevice()
            // 更新折叠状态
            isFoldableDevice = isFoldable
            
            if (isFoldable) {
                Log.d("FloatingWindowService", "设备是折叠屏")
                // 检查设备是否处于折叠状态的代码待实现
                if (checkDeviceFolded()) {
                    updateFoldedScreenSafeArea()
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "更新屏幕尺寸失败", e)
        }
    }
    
    // 检查设备是否为折叠屏
    private fun checkFoldableDevice(): Boolean {
        // 这里应该实现检测设备是否为折叠屏的逻辑
        // 目前简单返回false，后续可以根据实际需求实现
        return false
    }
    
    // 检查设备是否处于折叠状态
    private fun checkDeviceFolded(): Boolean {
        // 这里应该实现检测设备折叠状态的逻辑
        // 目前简单返回false，后续可以根据实际需求实现
        return false
    }
    
    // 更新折叠屏安全区域
    private fun updateFoldedScreenSafeArea() {
        // 这里应该实现更新折叠屏安全区域的逻辑
        // 目前仅打印日志，后续可以根据实际需求实现
        Log.d("FloatingWindowService", "更新折叠屏安全区域")
    }
    
    // 调整悬浮球位置确保在屏幕范围内
    private fun adjustFloatingBallPosition() {
        if (floatingBallView == null || windowManager == null) return
        
        try {
            val params = floatingBallView?.layoutParams as? WindowManager.LayoutParams ?: return
            val ballWidth = floatingBallView?.width ?: 0
            val ballHeight = floatingBallView?.height ?: 0
            
            // 确保悬浮球在屏幕范围内
            params.x = params.x.coerceIn(-ballWidth/3, screenWidth - ballWidth*2/3)
            params.y = params.y.coerceIn(0, screenHeight - ballHeight)
            
            windowManager?.updateViewLayout(floatingBallView, params)
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "调整悬浮球位置失败", e)
        }
    }
    
    // 重新定位菜单
    private fun repositionMenu() {
        // 暂时留空，后续实现
        Log.d("FloatingWindowService", "重新定位菜单")
    }

    // 更新半圆状态下的位置
    private fun updateEdgePosition() {
        // 由于HalfCircleFloatingWindow的isHidden和edgePosition是私有属性
        // 我们需要等待HalfCircleFloatingWindow类添加公开方法后再实现此功能
        Log.d("FloatingWindowService", "半圆位置更新功能暂未启用")
    }

    private fun updateMenuLayout(layout: String) {
        // 根据布局类型更新悬浮菜单的显示方式
        when(layout) {
            "alphabetical" -> {
                // 实现字母索引排列布局
                // 1. 对菜单项按照首字母分组
                // 2. 显示字母索引列表
                // 3. 根据选中的字母显示对应的图标
            }
            else -> {
                // 实现混合排列布局（默认布局）
                // 直接显示所有图标
            }
        }
    }

    // 添加缺失的菜单项获取方法
    private fun getRecentItems(): List<MenuItem> {
        return menuItemsList.filter { it.isEnabled }
            .sortedByDescending { it.lastUsed }
            .take(8)
    }

    private fun getAIAssistants(): List<MenuItem> {
        return menuItemsList.filter { it.category == MenuCategory.AI_SEARCH && it.isEnabled }
    }

    private fun getSearchEngines(): List<MenuItem> {
        return menuItemsList.filter { it.category == MenuCategory.NORMAL_SEARCH && it.isEnabled }
    }

    private fun getFunctions(): List<MenuItem> {
        return menuItemsList.filter { it.category == MenuCategory.FUNCTION && it.isEnabled }
    }

    // 添加菜单项动画方法
    private fun animateMenuItem(item: View, targetX: Float, targetY: Float, delay: Int) {
        item.animate()
            .x(targetX)
            .y(targetY)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setStartDelay(delay * 50L)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    // 显示AI菜单
    private fun showAIMenu() {
        try {
            if (menuContainer == null) {
                menuContainer = createMenuContainer()
                if (menuContainer != null) {
                    windowManager?.addView(menuContainer, menuParams)
                }
            }
            menuContainer?.visibility = View.VISIBLE
            menuContainer?.bringToFront()
            isMenuVisible = true
            
            // 显示输入法
            val searchEditText = menuContainer?.findViewById<EditText>(R.id.search_edit_text)
            searchEditText?.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing menu: ${e.message}")
        }
    }

    // 隐藏AI菜单
    private fun hideAIMenu() {
        if (!isMenuVisible) return
        isMenuVisible = false
        
        Log.d(TAG, "隐藏AI菜单")
        
        // 隐藏输入法
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(menuContainer?.windowToken, 0)
        
        // 隐藏菜单容器
        menuContainer?.visibility = View.GONE
    }

    private fun registerReceivers() {
        try {
            // 注册截图完成广播接收器
            val screenshotFilter = IntentFilter(ScreenshotActivity.ACTION_SCREENSHOT_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(
                    screenshotReceiver,
                    screenshotFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(screenshotReceiver, screenshotFilter)
            }
            
            // 注册主题变化的广播接收器
            val themeFilter = IntentFilter("com.example.aifloatingball.LAYOUT_THEME_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(themeReceiver, themeFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(themeReceiver, themeFilter)
            }
            
            // 注册菜单更新广播接收器
            val menuUpdateFilter = IntentFilter("com.example.aifloatingball.ACTION_UPDATE_MENU")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(menuUpdateReceiver, menuUpdateFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(menuUpdateReceiver, menuUpdateFilter)
            }
            
            // 注册配置变更监听器
            val configFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            registerReceiver(configurationChangeReceiver, configFilter)
            
            Log.d("FloatingService", "所有广播接收器注册成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "注册广播接收器失败", e)
        }
    }

    // 添加初始化方法
    private fun initWindowManager() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (windowManager == null) {
            throw IllegalStateException("无法初始化窗口管理器")
        }
    }
    
    private fun initScreenSize() {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            screenWidth = metrics?.bounds?.width() ?: 0
            screenHeight = metrics?.bounds?.height() ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
        
        if (screenWidth == 0 || screenHeight == 0) {
            throw IllegalStateException("无法获取屏幕尺寸")
        }
    }
    
    private fun initManagers() {
        // 初始化设置管理器
        settingsManager = SettingsManager.getInstance(this)
        
        // 初始化手势管理器
        gestureManager = GestureManager(this, this)
        
        // 初始化快捷菜单管理器
        quickMenuManager = QuickMenuManager(this, windowManager!!)
        
        // 初始化系统设置助手
        systemSettingsHelper = SystemSettingsHelper(this)
    }
}