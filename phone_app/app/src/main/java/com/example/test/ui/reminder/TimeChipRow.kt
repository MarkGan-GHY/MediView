package com.example.test.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 提醒时间卡片行组件。
 * 左侧 LazyRow 承载时间卡片列表，右侧固定 [+] 按钮。
 * [selectedIndex] 为 -1 表示无选中。
 */
@Composable
fun TimeChipRow(
    times: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(times, key = { _, time -> time }) { index, time ->
                TimeChip(
                    time = time,
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    onDelete = { onDelete(index) }
                )
            }
        }

        // [+] 按钮
        IconButton(
            onClick = onAdd,
            modifier = Modifier
                .size(40.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                )
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加提醒时间",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------- 单个时间卡片 ----------

@Composable
private fun TimeChip(
    time: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val bgColor =
        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface
    val textColor =
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .background(bgColor, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(22.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除此时间",
                modifier = Modifier.size(14.dp),
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else Color.Gray
            )
        }
    }
}
