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

class FloatingWindowService : Service(), GestureManager.GestureCallback {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val MEMORY_CHECK_INTERVAL = 30000L // 30秒检查一次内存
        private const val REQUEST_SCREENSHOT = 1001
        private const val EDGE_SNAP_THRESHOLD = 32f // dp
        private const val FLING_MIN_VELOCITY = 500f // 最小甩动速度
        private const val ANIMATION_DURATION = 250L // 动画时长
    }

    private var windowManager: WindowManager? = null
    private var floatingBallView: View? = null
    private lateinit var gestureManager: GestureManager
    private lateinit var quickMenuManager: QuickMenuManager
    private lateinit var systemSettingsHelper: SystemSettingsHelper
    private var screenWidth = 0
    private var screenHeight = 0
    private var currentEngineIndex = 0
    
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
    private val aiEngines = listOf(
        AIEngine("ChatGPT", R.drawable.ic_chatgpt, "https://chat.openai.com"),
        AIEngine("Claude", R.drawable.ic_claude, "https://claude.ai"),
        AIEngine("文心一言", R.drawable.ic_wenxin, "https://yiyan.baidu.com"),
        AIEngine("通义千问", R.drawable.ic_qianwen, "https://qianwen.aliyun.com"),
        AIEngine("讯飞星火", R.drawable.ic_xinghuo, "https://xinghuo.xfyun.cn"),
        AIEngine("返回", R.drawable.ic_back, "back://last_app") // 添加返回功能
    )
    
    data class AIEngine(
        val name: String,
        val iconRes: Int,
        val url: String
    )
    
    private var isMenuVisible = false
    private val menuItems = mutableListOf<View>()
    private var menuContainer: View? = null
    private val MENU_ITEM_SIZE_DP = 56 // 增大菜单项尺寸
    private val MENU_SPACING_DP = 8 // 菜单项间距
    private val MENU_MARGIN_DP = 16 // 菜单与悬浮球的距离

    private var webViewContainer: View? = null
    private var webView: WebView? = null
    private var isWebViewVisible = false
    private val WEBVIEW_WIDTH_DP = 300  // 悬浮窗宽度
    private val WEBVIEW_HEIGHT_DP = 500 // 悬浮窗高度

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        try {
            startTime = System.currentTimeMillis()
            setupMemoryMonitoring()
            Log.d("FloatingService", "服务开始创建")
            super.onCreate()
            
            // 初始化剪贴板管理器并立即开始监听
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            
            // 检查初始剪贴板内容
            Handler(Looper.getMainLooper()).post {
                try {
                    val clipData = clipboardManager.primaryClip
                    val clipText = clipData?.getItemAt(0)?.text?.toString()?.trim()
                    
                    if (!clipText.isNullOrEmpty()) {
                        Log.d("FloatingService", "检测到初始剪贴板内容: $clipText")
                        showOverlayDialog(clipText)
                    }
                } catch (e: Exception) {
                    Log.e("FloatingService", "检查初始剪贴板内容失败", e)
                }
            }
            
            // 注册截图完成广播接收器
            val filter = IntentFilter(ScreenshotActivity.ACTION_SCREENSHOT_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(
                    screenshotReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(screenshotReceiver, filter)
            }
            Log.d("FloatingService", "广播接收器注册成功")
            
            // 初始化 SettingsManager
            settingsManager = SettingsManager.getInstance(this)
            
            // 注册主题变化的广播接收器
            val themeFilter = IntentFilter("com.example.aifloatingball.LAYOUT_THEME_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(themeReceiver, themeFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(themeReceiver, themeFilter)
            }
            
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
            
            // 创建菜单容器
            createMenuContainer()
            
            // Initialize GestureDetector
            gestureDetector = GestureDetector(this, object : GestureDetector.OnGestureListener {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onShowPress(e: MotionEvent) {
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    return false
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    return false
                }

                override fun onLongPress(e: MotionEvent) {
                    // 长按时显示AI菜单而不是截图模式
                    vibrate(200)
                    showAIMenu()
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    return false
                }
            })
            
            // 创建并显示悬浮球
            try {
            createFloatingBall()
            } catch (e: Exception) {
                Log.e("FloatingService", "创建悬浮球失败", e)
                Toast.makeText(this, "创建悬浮球失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            
            // 尝试启动前台服务
            try {
            startForegroundOrNormal()
            } catch (e: Exception) {
                Log.e("FloatingService", "启动前台服务失败", e)
                // 即使前台服务启动失败，也继续运行
            }
            
            Log.d("FloatingService", "服务创建完成")
        } catch (e: Exception) {
            Log.e("FloatingService", "服务创建失败", e)
            Toast.makeText(this, "服务创建失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
    
    private fun initWindowManager() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (windowManager == null) {
                throw IllegalStateException("无法获取WindowManager服务")
            }
            Log.d("FloatingService", "窗口管理器初始化成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "窗口管理器初始化失败", e)
            throw e
        }
    }
    
    private fun initScreenSize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager?.currentWindowMetrics
                windowMetrics?.bounds?.let {
                    screenWidth = it.width()
                    screenHeight = it.height()
                }
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
            }
            
            if (screenWidth == 0 || screenHeight == 0) {
                throw IllegalStateException("无法获取屏幕尺寸")
            }
            Log.d("FloatingService", "屏幕尺寸: ${screenWidth}x${screenHeight}")
            cardSpacing = 60.dpToPx()
        } catch (e: Exception) {
            Log.e("FloatingService", "获取屏幕尺寸失败", e)
            throw e
        }
    }
    
    private fun initManagers() {
        try {
            systemSettingsHelper = SystemSettingsHelper(this)
            gestureManager = GestureManager(this, this)
            quickMenuManager = QuickMenuManager(this, windowManager!!)
            Log.d("FloatingService", "所有管理器初始化成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "初始化管理器失败", e)
            throw e
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
            
            startForeground(NOTIFICATION_ID, notification)
            Log.d("FloatingService", "前台服务启动成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "前台服务启动失败", e)
            
            // 显示错误提示
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this, 
                    "前台服务启动失败：${e.message}\n将以普通服务运行", 
                    Toast.LENGTH_LONG
                ).show()
                
                // 尝试以普通服务启动
                try {
                    startService(Intent(this, FloatingWindowService::class.java))
                } catch (serviceStartException: Exception) {
                    Log.e("FloatingService", "普通服务启动失败", serviceStartException)
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_BALL_SIZE" -> {
                val size = intent.getIntExtra("size", 50)
                updateFloatingBallSize(size)
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
            
            // 设置悬浮球布局参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                        
                        // 设置长按检测
                        longPressRunnable = Runnable {
                            isLongPress = true
                            vibrate(200)
                            showAIMenu()
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, 500) // 500ms长按阈值
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        
                        // 如果移动距离超过阈值，取消长按
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            longPressHandler.removeCallbacks(longPressRunnable!!)
                        }
                        
                        if (!isLongPress) {
                            params.x = (initialX + deltaX).toInt()
                            params.y = (initialY + deltaY).toInt()
                            
                            params.x = params.x.coerceIn(-view.width / 3, screenWidth - view.width * 2 / 3)
                            params.y = params.y.coerceIn(0, screenHeight - view.height)
                            
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
                        
                        if (!isLongPress) {
                            if (abs(event.rawX - initialTouchX) < 5 && abs(event.rawY - initialTouchY) < 5) {
                                // 点击事件，打开悬浮窗并加载默认主页
                                val intent = Intent(this@FloatingWindowService, SearchActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    // 添加标记，表示从悬浮球打开
                                    putExtra("from_floating_ball", true)
                                }
                                startActivity(intent)
                            } else {
                                // 处理边缘吸附
                                val distanceToLeftEdge = params.x + view.width / 3
                                val distanceToRightEdge = screenWidth - (params.x + view.width * 2 / 3)
                                
                                when {
                                    distanceToLeftEdge <= edgeSnapThresholdPx -> snapToEdge(params, true)
                                    distanceToRightEdge <= edgeSnapThresholdPx -> snapToEdge(params, false)
                                }
                            }
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
            
            // 初始化时应用主题
            updateFloatingBallTheme()
            
        } catch (e: Exception) {
            Log.e("FloatingService", "创建悬浮球失败", e)
            Toast.makeText(this, "创建悬浮球失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
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
            menuItems.clear()
            
            System.gc()
            
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
            
            // 只处理水平方向的边缘吸附
            val distanceToLeftEdge = params.x + view.width / 3
            val distanceToRightEdge = screenWidth - (params.x + view.width * 2 / 3)
            
            when {
                distanceToLeftEdge <= edgeSnapThresholdPx -> snapToEdge(params, true)
                distanceToRightEdge <= edgeSnapThresholdPx -> snapToEdge(params, false)
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
                    // 只检查水平方向的边缘吸附
                    val distanceToLeftEdge = params.x + view.width / 3
                    val distanceToRightEdge = screenWidth - (params.x + view.width * 2 / 3)
                    
                    when {
                        distanceToLeftEdge <= edgeSnapThresholdPx * 1.5f -> snapToEdge(params, true)
                        distanceToRightEdge <= edgeSnapThresholdPx * 1.5f -> snapToEdge(params, false)
                    }
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            
            start()
        }
    }
    
    private fun snapToEdge(params: WindowManager.LayoutParams, toLeft: Boolean) {
        edgeSnapAnimator?.cancel()
        
        val view = floatingBallView ?: return
        val startX = params.x
        val targetX = if (toLeft) -view.width / 3 else screenWidth - view.width * 2 / 3
        
        // 计算移动距离，用于调整动画参数
        val distance = abs(targetX - startX)
        
        edgeSnapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            // 根据距离调整动画时长，距离越长动画越慢
            duration = min(150L + (distance / 3), 300L)
            
            // 根据距离选择合适的插值器
            interpolator = if (distance > screenWidth / 4) {
                // 大距离使用减速插值器，更平滑
                DecelerateInterpolator(1.5f)
            } else {
                // 小距离使用弱化的回弹效果
                OvershootInterpolator(0.3f)
            }
            
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e("FloatingService", "更新悬浮球位置失败", e)
                }
            }
            
            start()
        }
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
    private fun createMenuContainer() {
        try {
            val container = FrameLayout(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            
            container.visibility = View.GONE
            
            // 创建菜单项
            aiEngines.forEachIndexed { index, engine ->
                val menuItem = createMenuItem(engine, index)
                menuItems.add(menuItem)
                container.addView(menuItem)
            }
            
            // 设置点击外部区域关闭菜单
            container.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    hideAIMenu()
                    true
                } else false
            }
            
            menuContainer = container
            windowManager?.addView(container, params)
            
            Log.d("FloatingService", "菜单容器创建成功")
        } catch (e: Exception) {
            Log.e("FloatingService", "创建菜单容器失败", e)
        }
    }
    
    // 创建菜单项
    private fun createMenuItem(engine: AIEngine, index: Int): View {
        val menuItem = LayoutInflater.from(this).inflate(R.layout.menu_item, null)
        
        // 设置布局参数
        menuItem.layoutParams = FrameLayout.LayoutParams(
            MENU_ITEM_SIZE_DP.dpToPx(),
            MENU_ITEM_SIZE_DP.dpToPx()
        )
        
        // 设置图标和文字
        val icon = menuItem.findViewById<ImageView>(R.id.icon)
        val name = menuItem.findViewById<TextView>(R.id.name)
        
        icon.setImageResource(engine.iconRes)
        name.text = engine.name
        
        // 初始状态
        menuItem.visibility = View.GONE
        menuItem.alpha = 0f
        menuItem.scaleX = 0f
        menuItem.scaleY = 0f
        
        // 点击事件
        menuItem.setOnClickListener {
            // 添加点击反馈动画
            menuItem.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(100)
                .withEndAction {
                    menuItem.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .withEndAction {
                            hideAIMenu()
                            openAIEngine(engine)
                        }
                        .start()
                }
                .start()
        }
        
        return menuItem
    }
    
    // 显示AI菜单
    private fun showAIMenu() {
        if (isMenuVisible) return
        isMenuVisible = true
        
        Log.d("FloatingService", "显示AI菜单")
        
        menuContainer?.visibility = View.VISIBLE
        
        // 获取悬浮球位置
        val ballLocation = IntArray(2)
        floatingBallView?.getLocationOnScreen(ballLocation)
        
        val ballCenterX = ballLocation[0] + (floatingBallView?.width ?: 0) / 2
        val ballCenterY = ballLocation[1] + (floatingBallView?.height ?: 0) / 2
        
        // 确定菜单显示在悬浮球的哪一侧（左侧或右侧）
        val showOnRight = ballCenterX < screenWidth / 2
        
        // 计算菜单起始位置
        val menuMargin = MENU_MARGIN_DP.dpToPx()
        val menuItemSize = MENU_ITEM_SIZE_DP.dpToPx()
        val menuSpacing = MENU_SPACING_DP.dpToPx()
        
        // 计算菜单项总高度
        val totalMenuHeight = menuItems.size * menuItemSize + (menuItems.size - 1) * menuSpacing
        
        // 计算菜单顶部位置，使菜单垂直居中对齐悬浮球
        var menuTopY = ballCenterY - totalMenuHeight / 2
        
        // 确保菜单不超出屏幕边界
        menuTopY = menuTopY.coerceIn(0, screenHeight - totalMenuHeight)
        
        // 计算菜单水平位置
        val menuX = if (showOnRight) {
            ballCenterX + (floatingBallView?.width ?: 0) / 2 + menuMargin
        } else {
            ballCenterX - (floatingBallView?.width ?: 0) / 2 - menuMargin - menuItemSize
        }
        
        // 显示菜单项
        menuItems.forEachIndexed { index, item ->
            val itemY = menuTopY + index * (menuItemSize + menuSpacing)
            
            // 设置初始位置（从悬浮球位置开始）
            item.visibility = View.VISIBLE
            item.alpha = 0f
            item.scaleX = 0.5f
            item.scaleY = 0.5f
            item.x = (ballCenterX - menuItemSize / 2).toFloat()
            item.y = (ballCenterY - menuItemSize / 2).toFloat()
            
            // 执行显示动画
            item.animate()
                .x(menuX.toFloat())
                .y(itemY.toFloat())
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(OvershootInterpolator(1.2f))
                .setStartDelay((index * 50).toLong())
                .start()
        }
    }
    
    // 隐藏AI菜单
    private fun hideAIMenu() {
        if (!isMenuVisible) return
        isMenuVisible = false
        
        Log.d("FloatingService", "隐藏AI菜单")
        
        // 获取悬浮球位置
        val ballLocation = IntArray(2)
        floatingBallView?.getLocationOnScreen(ballLocation)
        
        val ballCenterX = ballLocation[0] + (floatingBallView?.width ?: 0) / 2
        val ballCenterY = ballLocation[1] + (floatingBallView?.height ?: 0) / 2
        
        menuItems.forEachIndexed { index, item ->
            // 执行收起动画，回到悬浮球位置
            item.animate()
                .x((ballCenterX - item.width / 2).toFloat())
                .y((ballCenterY - item.height / 2).toFloat())
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .setStartDelay((menuItems.size - 1 - index) * 30L) // 反向延迟，使菜单项按相反顺序收起
                .withEndAction {
                    item.visibility = View.GONE
                }
                .start()
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            menuContainer?.visibility = View.GONE
        }, ANIMATION_DURATION + menuItems.size * 30L)
    }
    
    // 打开AI引擎
    private fun openAIEngine(engine: AIEngine) {
        try {
            Log.d("FloatingService", "打开: ${engine.name}")
            
            when {
                engine.url == "back://last_app" -> {
                    // 处理返回功能
                    handleBackToLastApp()
                }
                else -> {
                    // 原有的网页打开逻辑
                    val intent = Intent(this, FloatingWebViewService::class.java).apply {
                        putExtra("url", engine.url)
                        putExtra("from_ai_menu", true)
                    }
                    startService(intent)
                }
            }
            
        } catch (e: Exception) {
            Log.e("FloatingService", "打开失败", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
}