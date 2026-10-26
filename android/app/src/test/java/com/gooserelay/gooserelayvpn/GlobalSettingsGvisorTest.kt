package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.gooserelay.gooserelayvpn.util.GlobalSettings
import com.gooserelay.gooserelayvpn.util.parseImportedSettings
import org.junit.Test

class GlobalSettingsGvisorTest {
    @Test
    fun import_missingField_defaultsGvisorEngineToTrue() {
        val parsed = parseImportedSettings("{}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isTrue()
    }

    @Test
    fun roundTrip_preservesGvisorEngineEnabledFalse() {
        val json = Gson().toJson(GlobalSettings(gvisorEngineEnabled = false))
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isFalse()
    }

    @Test
    fun import_wronglyTypedGvisorEngine_fallsBackToDefault() {
        val parsed = parseImportedSettings("{\"gvisorEngineEnabled\":\"yes\"}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.gvisorEngineEnabled).isTrue()
    }
}
