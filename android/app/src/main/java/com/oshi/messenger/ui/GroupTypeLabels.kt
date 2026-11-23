package com.oshi.messenger.ui

import android.content.Context
import androidx.annotation.StringRes
import com.oshi.messenger.R
import com.oshi.messenger.data.model.GroupType

/**
 * The localized name and one-line description of a [GroupType].
 *
 * `GroupType` used to carry these as English string literals on the enum itself, which
 * is the only thing a data-layer enum can carry — and the app ships 34 languages. The
 * last reader was the groups search box (`GroupsViewModel.filterGroups`), so typing the
 * word for "Public" in any locale but English matched nothing.
 *
 * Screens resolve these through `stringResource`; `GroupsViewModel` resolves them
 * through the application `Context`, which is why the resource ids are exposed as well
 * as the strings.
 */
object GroupTypeLabels {

    @StringRes
    fun labelRes(type: GroupType): Int = when (type) {
        GroupType.ADMIN_ONLY -> R.string.group_type_admin_only
        GroupType.COLLABORATIVE -> R.string.group_type_collaborative
        GroupType.PUBLIC -> R.string.group_type_public
    }

    @StringRes
    fun descriptionRes(type: GroupType): Int = when (type) {
        GroupType.ADMIN_ONLY -> R.string.group_type_admin_only_desc
        GroupType.COLLABORATIVE -> R.string.group_type_collaborative_desc
        GroupType.PUBLIC -> R.string.group_type_public_desc
    }

    fun label(context: Context, type: GroupType): String = context.getString(labelRes(type))
}
