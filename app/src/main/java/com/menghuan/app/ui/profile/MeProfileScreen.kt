package com.menghuan.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.MyProfile
import com.menghuan.app.data.UserAvatar
import com.menghuan.app.ui.common.AvatarEmojiWheel
import com.menghuan.app.ui.common.AvatarPill
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.drawRing
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberAvatarWheelState
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.settingsContainer
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ============================================================
// 我自己的档案（v0.80.0）
// ============================================================

data class DraftFact(val id: Long?, val key: String, val value: String)

/**
 * 我的头像（三态统一渲染）：有图用图 → 有 emoji 用 emoji → 都没设就是黑底白「我」圆。
 * 与动态页「我」那条、聊天页用户气泡旁的头像是同一张脸（同名同口径）。
 *
 * [ringBrush]（v0.93.0）：套一圈渐变环，**画法与人设头像那圈完全同一份**（`drawRing`），
 * 让人设头像与"我"的头像是同一套框样式。默认就带 —— "我"这张脸在档案页、我的页、
 * 动态页、聊天气泡旁都得长一样，靠调用点各传一次迟早漏掉一处。
 *
 * ⚠️ 这一张脸以前在动态页与聊天页各画了一份（同样的黑底「我」+ 同样的 NeuAvatar），
 * v0.93.0 一并收回这里：**同一张脸只有一份实现**。
 */
@Composable
fun MeAvatar(
    avatar: UserAvatar,
    size: androidx.compose.ui.unit.Dp,
    ringBrush: androidx.compose.ui.graphics.Brush? = meAvatarRingBrush(),
    modifier: Modifier = Modifier,
) {
    if (!avatar.isEmpty) {
        NeuAvatar(
            emoji = avatar.effectiveEmoji.ifBlank { "我" },
            size = size,
            ringBrush = ringBrush,
            imagePath = avatar.imagePath,
            modifier = modifier,
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(NeuAccent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ringBrush != null) {
                Box(Modifier.fillMaxSize().drawRing(ringBrush))
            }
            Text("我", fontSize = (size.value * 0.34f).sp, color = AccentInk, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 「我」的渐变环（v0.93.0）：与人设头像同一套 `personaBrush` 画法，配色按 id 稳定散列。
 *
 * 取一个**负数 id**：人设的 id 是 Room 自增的正数，不可能撞上，
 * 于是"我"的环色永远固定、也不会和某个角色重色。
 */
private const val ME_AVATAR_RING_ID = -1L

private fun meAvatarRingBrush(): androidx.compose.ui.graphics.Brush = personaBrush(ME_AVATAR_RING_ID)

/**
 * 个人档案页（v0.80.0）：头像 + 昵称 + 简介，三项都能改。
 *
 * 点进来的路径：我的 → 顶部档案卡 → 本页。
 * 与「基础档案」（ProfileFact 那张表）不是一个东西：那份是给 AI 读的画像，
 * 这份是"我在这个 App 里叫什么、写句什么话"。
 */
@Composable
fun MeProfileScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: MeViewModel = viewModel(factory = MeViewModel.Factory)
    val facts by vm.facts.collectAsState()
    val profile by app.settingsRepository.myProfile.collectAsState(initial = null)
    val avatar by app.settingsRepository.userAvatar.collectAsState(initial = null)

    // 档案条目是 Flow，首帧还是空表 → 只在**第一次拿到真实数据**时播种一次，
    // 之后绝不再覆盖，否则用户正在编辑的内容会被下一帧冲掉。
    val drafts = remember { mutableStateListOf<DraftFact>() }
    val removedIds = remember { mutableStateListOf<Long>() }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(facts) {
        if (!seeded && facts.isNotEmpty()) {
            facts.forEach { drafts.add(DraftFact(it.id, it.key, it.value)) }
            seeded = true
        }
    }

    // 换头像：复制进私有目录再存绝对路径（SAF 的读授权是会话级的，冷启动后 Uri 会失效）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dir = java.io.File(context.filesDir, "user_avatar").apply { mkdirs() }
            val target = java.io.File(dir, "me_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0L) {
                scope.launch {
                    app.settingsRepository.setUserAvatar((avatar ?: UserAvatar()).copy(imagePath = target.absolutePath))
                }
            }
        }
    }

    SettingsPage(title = "个人档案", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        // 头像：**大小与位置照旧**（88dp、居中），选择机制换成编辑人设那一套
        // （v0.80.0 用户点名"和人设头像选择的一样"，实现是同一份 `AvatarEmojiWheel`）——
        // emoji 排从框底下横穿、**左右各露一截**，滚到框心那个就是选中的；
        // 点框选图、下面一颗与头像同宽的细线胶囊（「点击编辑」/「恢复默认」）。
        // ⚠️ commitInitialAnchor = false：没选过头像的人，框里该一直是「我」，
        // 不能一进页面就被悄悄落成某个 emoji（那是实打实的 bug 观感）。
        val wheelState = rememberAvatarWheelState()
        // v0.93.0：「我」的头像框套上与人设头像**同一款**渐变环（配色固定，不跟角色走）
        val meRing = personaBrush(ME_AVATAR_RING_ID)
        AvatarEmojiWheel(
            emoji = avatar?.emoji ?: "",
            imagePath = avatar?.imagePath ?: "",
            onEmojiChange = { e ->
                // 选 emoji = 同时清掉图片（否则点了 emoji 发现头像没变，会以为坏了）；改动即时落盘
                scope.launch { app.settingsRepository.setUserAvatar(UserAvatar(emoji = e, imagePath = "")) }
            },
            onFrameClick = { picker.launch("image/*") },
            state = wheelState,
            ringBrush = meRing,
            frameSize = 88.dp,
            cellSize = 56.dp,
            lensSpan = 66.dp,
            emojiFontSize = 34.sp,
            bothSides = true,
            placeholder = "我",
            // 一样都没设过时，还是那张**黑底白「我」**的脸 —— 与聊天页 / 动态页里"我"同一张，
            // 也是手册里写明的样子（换成白面「我」会把它写错，两处也长得不一样）
            emptyFrame = { MeAvatar(avatar = UserAvatar(), size = 88.dp, ringBrush = meRing) },
            commitInitialAnchor = false,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            AvatarPill(
                text = if (avatar?.imagePath.orEmpty().isNotBlank()) "恢复默认" else "点击编辑",
                danger = avatar?.imagePath.orEmpty().isNotBlank(),
                width = 88.dp,
            ) {
                val cur = avatar ?: UserAvatar()
                if (cur.imagePath.isNotBlank()) {
                    scope.launch { app.settingsRepository.setUserAvatar(cur.copy(imagePath = "")) }
                } else {
                    picker.launch("image/*")
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        // 昵称 / 简介（v0.80.0）：形状换成编辑人设里「名字 / 身份」同款的 NeuTextField
        // （同一个组件、同一份排版，label 在框内左上）；落盘仍是**改完即时生效**，
        // 只是加了 400ms 防抖 —— 每敲一个字写一次 DataStore 没必要。
        // 草稿用可空标记：null = 还没动过这一格，显示库里的值；一旦编辑过就不再用库值覆盖，
        // 免得落盘返回的那一帧把正在输入的内容冲掉。
        var nickDraft by remember { mutableStateOf<String?>(null) }
        var bioDraft by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(nickDraft, bioDraft) {
            if (nickDraft == null && bioDraft == null) return@LaunchedEffect
            delay(400)
            // 写之前现读一次库里那份：昵称与简介各改各的，不能拿旧快照互相覆盖
            val cur = app.settingsRepository.myProfile.first() ?: MyProfile()
            app.settingsRepository.setMyProfile(
                cur.copy(
                    nickname = nickDraft ?: cur.nickname,
                    bio = bioDraft ?: cur.bio,
                ),
            )
        }
        NeuTextField(
            value = nickDraft ?: (profile?.nickname ?: ""),
            onValueChange = { if (it.length <= 16) nickDraft = it },
            label = "昵称",
            placeholder = "例：小满",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = bioDraft ?: (profile?.bio ?: ""),
            onValueChange = { if (it.length <= 60) bioDraft = it },
            label = "简介",
            placeholder = "例：在学做饭，最近迷上早起",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))

        // ===== 基础档案（v0.80.0：原「基础档案」行卡取消，条目并入本页）=====
        // 这些条目会注入所有角色的聊天 system prompt，是"AI 怎么认识我"，
        // 与上面的头像/昵称/简介（"我是谁"）放在同一页，入口只留一个。
        Text("基础档案 · 注入 AI 的用户画像", fontSize = 12.sp, color = MutedText, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        val sharedReadable by vm.profileSharedReadable.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("全部 AI 可读", fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            NeuSwitch(checked = sharedReadable, onToggle = { vm.setProfileSharedReadable(it) })
        }
        Spacer(Modifier.height(12.dp))
        drafts.forEachIndexed { index, draft ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NeuTextField(
                    value = draft.key,
                    onValueChange = { drafts[index] = draft.copy(key = it) },
                    placeholder = "项目",
                    modifier = Modifier.width(104.dp),
                )
                Spacer(Modifier.width(8.dp))
                NeuTextField(
                    value = draft.value,
                    onValueChange = { drafts[index] = draft.copy(value = it) },
                    placeholder = "内容",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .neuSurface(999.dp, inset = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "×",
                        fontSize = 17.sp,
                        color = MutedText,
                        modifier = Modifier
                            .clickable {
                                draft.id?.let { removedIds.add(it) }
                                drafts.removeAt(index)
                            }
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        NeuTextButton("＋ 添加一项", onClick = { drafts.add(DraftFact(null, "", "")) })
        Spacer(Modifier.height(18.dp))
        // 底部的「取消 / 保存」只管**档案条目**；昵称与简介是改动即时落盘的
        Row(Modifier.fillMaxWidth()) {
            NeuButton(text = "取消", onClick = onBack, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存条目",
                onClick = {
                    vm.saveEdits(drafts.toList(), removedIds.toList())
                    onBack()
                },
                kind = NeuButtonKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))

        Spacer(Modifier.height(10.dp))
    }
}
