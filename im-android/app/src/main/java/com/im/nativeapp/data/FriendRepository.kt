package com.im.nativeapp.data

import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.FriendApi
import com.im.nativeapp.core.network.FriendApplyRequest
import com.im.nativeapp.core.network.FriendRequestVO
import com.im.nativeapp.core.network.FriendVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 好友列表与好友申请（事实源：im-ui/src/api/friend.js）。
 * displayName 已经是后端「备注优先，其次昵称」的结果，直接渲染。
 */
@Singleton
class FriendRepository @Inject constructor(
    private val apiClient: ApiClient
) {
    private val _friends = MutableStateFlow<List<FriendVO>>(emptyList())
    val friends: StateFlow<List<FriendVO>> = _friends

    private val _received = MutableStateFlow<List<FriendRequestVO>>(emptyList())
    val received: StateFlow<List<FriendRequestVO>> = _received

    private val _sent = MutableStateFlow<List<FriendRequestVO>>(emptyList())
    val sent: StateFlow<List<FriendRequestVO>> = _sent

    /** 待处理申请数，导航红点 */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    suspend fun fetchFriends(keyword: String? = null) {
        val list: List<FriendVO> = apiClient.service(FriendApi::class.java).list(keyword?.takeIf { it.isNotBlank() }).unwrap(apiClient.json)
        _friends.value = list
    }

    suspend fun fetchRequests() {
        val r: List<FriendRequestVO> = apiClient.service(FriendApi::class.java).received().unwrap(apiClient.json)
        val s: List<FriendRequestVO> = apiClient.service(FriendApi::class.java).sent().unwrap(apiClient.json)
        _received.value = r
        _sent.value = s
        refreshPendingCount()
    }

    suspend fun refreshPendingCount() {
        runCatching {
            val count: Int = apiClient.service(FriendApi::class.java).pendingCount().unwrap(apiClient.json)
            _pendingCount.value = count
        }
    }

    /** 申请加好友：targetUserId 与 targetAccount 二选一 */
    suspend fun apply(targetAccount: String?, targetUserId: String?, verifyMessage: String?): Result<Unit> {
        return try {
            apiClient.service(FriendApi::class.java)
                .apply(FriendApplyRequest(targetUserId = targetUserId, targetAccount = targetAccount, verifyMessage = verifyMessage, source = "search"))
                .unwrapUnit()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 同意申请，返回新建的单聊会话 ID（可直接跳聊天） */
    suspend fun accept(id: String): Result<String?> {
        return try {
            val cid: String? = apiClient.service(FriendApi::class.java).accept(id).unwrap(apiClient.json)
            fetchRequests()
            Result.success(cid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reject(id: String): Result<Unit> {
        return try {
            apiClient.service(FriendApi::class.java).reject(id).unwrapUnit()
            fetchRequests()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFriend(friendId: String): Result<Unit> {
        return try {
            apiClient.service(FriendApi::class.java).removeFriend(friendId).unwrapUnit()
            _friends.value = _friends.value.filter { it.friendId != friendId }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 好友在线状态推送：同步列表里的 online 位 */
    fun applyOnlineState(userId: String, online: Boolean) {
        _friends.value = _friends.value.map {
            if (it.friendId == userId) it.copy(online = online) else it
        }
    }

    /** WS notify(friend-request) 触发：增量 +1 由 refreshPendingCount 拉回权威值 */
    suspend fun onFriendRequestNotify() = refreshPendingCount()

    fun reset() {
        _friends.value = emptyList()
        _received.value = emptyList()
        _sent.value = emptyList()
        _pendingCount.value = 0
    }
}
