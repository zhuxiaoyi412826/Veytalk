package com.im.nativeapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.im.nativeapp.core.call.CallManager
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.data.WsDispatcher
import com.im.nativeapp.ui.call.CallOverlay
import com.im.nativeapp.ui.chat.ChatScreen
import com.im.nativeapp.ui.friend.AddFriendScreen
import com.im.nativeapp.ui.friend.FriendRequestsScreen
import com.im.nativeapp.ui.group.CreateGroupScreen
import com.im.nativeapp.ui.home.MainScreen
import com.im.nativeapp.ui.login.LoginScreen
import com.im.nativeapp.ui.live.LiveRoomScreen
import com.im.nativeapp.ui.theme.ImTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionStore

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var wsDispatcher: WsDispatcher

    @Inject
    lateinit var callManager: CallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题用可变状态承接：「我的」里改完立即重排生效，不必重启
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            // 设置页改完回写这个状态（SettingsStore 已是单一事实源，这里只做触发）
            LaunchedEffect(Unit) {
                com.im.nativeapp.ui.theme.ThemeEvents.changes.collect { themeMode = settings.themeMode }
            }
            ImTheme(themeMode = themeMode) {
                val nav = rememberNavController()
                // 冷启动：有 token 先进行为主页（各仓库首拉失败会由 1002/2010 回登录页）
                val start = if (session.isLoggedIn()) "main" else "login"

                // 被顶下线：弹提示并强制回登录页（对齐 dispatch.js 的 kickout 处理）
                LaunchedEffect(Unit) {
                    wsDispatcher.kickout.collect { reason ->
                        Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                        nav.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = start) {
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = {
                                nav.navigate("main") { popUpTo("login") { inclusive = true } }
                            }
                        )
                    }
                    composable("main") {
                        MainScreen(
                            onOpenChat = { cid -> nav.navigate("chat/$cid") },
                            onOpenFriendRequests = { nav.navigate("friend-requests") },
                            onOpenAddFriend = { nav.navigate("add-friend") },
                            onOpenCreateGroup = { nav.navigate("group-create") },
                            onOpenLiveRoom = { roomId -> nav.navigate("live/$roomId") },
                            onOpenAi = { nav.navigate("ai") },
                            onOpenProfile = { nav.navigate("profile-edit") },
                            onLogout = {
                                nav.navigate("login") { popUpTo("main") { inclusive = true } }
                            }
                        )
                    }
                    composable(
                        route = "chat/{conversationId}",
                        arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                    ) {
                        ChatScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        route = "live/{roomId}",
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType })
                    ) {
                        LiveRoomScreen(roomId = it.arguments?.getString("roomId") ?: "", onBack = { nav.popBackStack() })
                    }
                    composable("friend-requests") {
                        FriendRequestsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChat = { cid ->
                                nav.navigate("chat/$cid") { popUpTo("main") }
                            }
                        )
                    }
                    composable("add-friend") {
                        AddFriendScreen(onBack = { nav.popBackStack() })
                    }
                    composable("ai") {
                        com.im.nativeapp.ui.ai.AiScreen(onBack = { nav.popBackStack() })
                    }
                    composable("profile-edit") {
                        com.im.nativeapp.ui.profile.ProfileEditScreen(onBack = { nav.popBackStack() })
                    }
                    composable("group-create") {
                        CreateGroupScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChat = { cid ->
                                nav.navigate("chat/$cid") { popUpTo("main") }
                            }
                        )
                    }
                }

                // 通话浮层：盖在一切路由之上，CallManager 状态驱动显隐
                CallOverlay(callManager)
                }
            }
        }
    }
}
