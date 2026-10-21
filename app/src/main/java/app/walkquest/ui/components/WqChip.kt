package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Чип WalkQuest — плашка-пилюля со статусом (DESIGN: чипы состояния записи и
 * локации; навигация — surface-raised pill над картой с тенью 4dp).
 *
 * Состояния записи (DESIGN): «Идёт запись» — точка-индикатор акцентом (со
 * свечением-смыслом «запись идёт», без анимации-мигания), «На паузе» —
 * нейтральная dim-точка. Локация: «Определяю местоположение…» — без точки.
 * Текст — meta (12sp, минимальный кегль), цвет ink-secondary.
 */

@Composable
fun WqChip(
    text: String,
    modifier: Modifier = Modifier,
    /** Точка-индикатор слева; null — без точки. Акцент = активность, dim = пауза. */
    dotColor: Color? = null,
    /** Мягкое свечение точки — только акцентные индикаторы (DESIGN Elevation: свечение — привилегия акцентных точек; «На паузе» — без свечения). */
    dotGlow: Boolean = false,
    /** Цвет текста; по умолчанию ink-secondary, чип записи — акцентом (макет .live). */
    textColor: Color = WqTheme.colors.inkSecondary,
    /** Опциональная иконка слева вместо/вместе с точкой. */
    leadingIcon: ImageVector? = null,
    leadingIconContentDescription: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = WqTheme.shapes.full,
        color = WqTheme.colors.surfaceRaised,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dotColor != null) {
                Surface(
                    shape = CircleShape,
                    color = dotColor,
                    shadowElevation = if (dotGlow) 6.dp else 0.dp,
                    modifier = Modifier.size(8.dp),
                ) {}
            }
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = leadingIconContentDescription,
                    tint = WqTheme.colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                color = textColor,
                style = WqTheme.typography.meta,
            )
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqChipPreview() {
    WalkQuestTheme {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WqChip(text = "Идёт запись", dotColor = WqTheme.colors.accentText)
            WqChip(text = "На паузе", dotColor = WqTheme.colors.inkDim)
            WqChip(text = "Определяю местоположение…")
            WqChip(
                text = "План",
                leadingIcon = WalkQuestIcons.Plus,
                leadingIconContentDescription = null,
            )
        }
    }
}
