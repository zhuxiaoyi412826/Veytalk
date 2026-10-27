package com.im.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ConversationVO
import com.im.nativeapp.data.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val repo: ConversationRepository,
    private val session: SessionStore
) : ViewModel() {

    val list = repo.list
    val loading = repo.loading

    init {
        viewModelScope.launch { runCatching { repo.fetchList() } }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repo.fetchList() } }
    }

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    fun toggleTop(item: ConversationVO, enabled: Boolean) {
        val cid = item.conversationId ?: return
        viewModelScope.launch { runCatching { repo.toggleTop(cid, enabled) } }
    }

    fun toggleMute(item: ConversationVO, enabled: Boolean) {
        val cid = item.conversationId ?: return
        viewModelScope.launch { runCatching { repo.toggleMute(cid, enabled) } }
    }

    fun remove(item: ConversationVO) {
        val cid = item.conversationId ?: return
        viewModelScope.launch { runCatching { repo.remove(cid) } }
    }
}
