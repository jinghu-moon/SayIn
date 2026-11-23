package seeyuer.sayin.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRouteTest {
    @Test fun storageIsOpenedFromAndReturnsToRoot() {
        assertEquals(SettingsRoute.Root, SettingsRoute.Storage.parent)
    }

    @Test fun localModelsReturnsToStorage() {
        assertEquals(SettingsRoute.Storage, SettingsRoute.LocalModels.parent)
    }
}
