package com.daoshi.app.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.ProfileRepository
import com.daoshi.app.data.model.ProfileFact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 编辑弹层的草稿行：id = null 表示新增 */
data class DraftFact(val id: Long?, val key: String, val value: String)

/**
 * 我的数据页 VM：基础档案接 ProfileFact（此前整页为静态演示数据）。
 * 编辑保存：删除被移除的行，非空行 upsert（保留已有行的 source）。
 */
class DataViewModel(private val repo: ProfileRepository) : ViewModel() {

    val facts: StateFlow<List<ProfileFact>> = repo.observeFacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveEdits(items: List<DraftFact>, removedIds: List<Long>) = viewModelScope.launch {
        removedIds.forEach { repo.delete(it) }
        items.forEach { draft ->
            val key = draft.key.trim()
            val value = draft.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            val existing = draft.id?.let { repo.getFact(it) }
            repo.save(
                ProfileFact(
                    id = draft.id ?: 0,
                    key = key.ifBlank { value },
                    value = value,
                    source = existing?.source ?: "manual",
                )
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                DataViewModel(app.profileRepository)
            }
        }
    }
}
