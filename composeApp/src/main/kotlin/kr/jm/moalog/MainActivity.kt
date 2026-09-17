package kr.jm.moalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kr.jm.moalog.di.initKoin
import kr.jm.moalog.appshell.appShellModule
import org.koin.android.ext.koin.androidContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initKoin {
            androidContext(applicationContext)
            modules(appShellModule)
        }
        setContent { MoaLogApp() }
    }
}
