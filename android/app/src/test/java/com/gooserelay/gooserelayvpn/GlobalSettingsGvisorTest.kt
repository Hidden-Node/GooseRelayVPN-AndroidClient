package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.gooserelay.gooserelayvpn.util.GlobalSettings
import com.gooserelay.gooserelayvpn.util.parseImportedSettings
import org.junit.Test

class GlobalSettingsGvisorTest {
    @Test
    fun import_missingField_defaultsGvisorEngineToFalse() {
        val parsed = parseImportedSettings("{}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isFalse()
    }

    @Test
    fun roundTrip_preservesGvisorEngineEnabledTrue() {
        val json = Gson().toJson(GlobalSettings(gvisorEngineEnabled = true))
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isTrue()
    }

    @Test
    fun import_wronglyTypedGvisorEngine_fallsBackToDefault() {
        val parsed = parseImportedSettings("{\"gvisorEngineEnabled\":\"yes\"}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isFalse()
    }
}
