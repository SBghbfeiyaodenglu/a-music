package com.amusic.player

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.amusic.player.ui.MainScreen
import com.amusic.player.ui.theme.AMusicTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本应用深色优先，强制系统栏使用浅色图标。
        // 否则手机处于浅色模式时，状态栏图标会是深色，在深色背景上同样看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // 容器放进程级单例，屏幕旋转重建 Activity 时不会把播放器一起释放
        val container = AppGraph.init(this)

        setContent {
            AMusicTheme {
                // 必须在最外层套 Surface。
                // Material3 里 LocalContentColor 的默认值是黑色，只有 Surface 才会把它改成
                // 跟随主题的前景色。少了这一层，所有没有显式指定颜色的图标和文字都会被画成
                // 黑色，在深色背景上完全看不见。
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MainScreen(container)
                }
            }
        }
    }
}
