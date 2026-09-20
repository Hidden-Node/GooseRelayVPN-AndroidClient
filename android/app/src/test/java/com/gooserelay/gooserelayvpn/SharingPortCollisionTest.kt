package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.ui.settings.sharingPortCollision
import org.junit.Test

class SharingPortCollisionTest {
    @Test
    fun `equal ports with sharing enabled collide`() {
        assertThat(sharingPortCollision(8090, 8090, true)).isTrue()
    }

    @Test
    fun `different ports never collide`() {
        assertThat(sharingPortCollision(8090, 8091, true)).isFalse()
    }

    @Test
    fun `collision ignored when sharing disabled`() {
        assertThat(sharingPortCollision(8090, 8090, false)).isFalse()
    }

    @Test
    fun `null ports never collide`() {
        assertThat(sharingPortCollision(null, 8090, true)).isFalse()
    }
}
