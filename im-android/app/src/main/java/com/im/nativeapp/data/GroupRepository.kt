package com.im.nativeapp.data

import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.GroupApi
import com.im.nativeapp.core.network.GroupCreateRequest
import com.im.nativeapp.core.network.GroupJoinRequest
import com.im.nativeapp.core.network.GroupVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 我的群聊与建群（事实源：im-ui/src/api/group.js） */
@Singleton
class GroupRepository @Inject constructor(
    private val apiClient: ApiClient
) {
    private val _myGroups = MutableStateFlow<List<GroupVO>>(emptyList())
    val myGroups: StateFlow<List<GroupVO>> = _myGroups

    suspend fun fetchMyGroups() {
        val list: List<GroupVO> = apiClient.service(GroupApi::class.java).my().unwrap(apiClient.json)
        _myGroups.value = list
    }

    /** 创建群，返回的 GroupVO 带 conversationId 可直接跳聊天 */
    suspend fun create(name: String, memberIds: List<String>): Result<GroupVO> {
        return try {
            val vo: GroupVO = apiClient.service(GroupApi::class.java)
                .create(GroupCreateRequest(name = name, memberIds = memberIds))
                .unwrap(apiClient.json)
            fetchMyGroups()
            Result.success(vo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 凭邀请码申请入群：未开审批直接入群(joined)，开了审批待管理员确认(pending) */
    suspend fun joinByCode(inviteCode: String): Result<Unit> {
        return try {
            apiClient.service(GroupApi::class.java).join(GroupJoinRequest(inviteCode = inviteCode)).unwrapUnit()
            fetchMyGroups()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun quit(groupId: String): Result<Unit> {
        return try {
            apiClient.service(GroupApi::class.java).quit(groupId).unwrapUnit()
            fetchMyGroups()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun reset() {
        _myGroups.value = emptyList()
    }
}
