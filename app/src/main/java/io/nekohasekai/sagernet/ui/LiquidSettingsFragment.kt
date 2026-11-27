// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ui.theme.LiquidPrefs

/**
 * Fork-only settings screen for the Liquid Glass UI, backed by its own SharedPreferences file
 * ("liquid_ui") so upstream settings changes never collide with it.
 */
class LiquidSettingsFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "liquid_ui"
        setPreferencesFromResource(R.xml.liquid_preferences, rootKey)

        // The master switch changes every surface at once: restart the process so the whole app picks it up.
        findPreference<SwitchPreferenceCompat>("enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == false || newValue == true) {
                triggerFullRestart(requireContext())
            }
            true
        }

        findPreference<Preference>("respect_power_save")?.setOnPreferenceChangeListener { _, _ -> true }
    }
}
