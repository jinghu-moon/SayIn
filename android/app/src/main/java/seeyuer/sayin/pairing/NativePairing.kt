package seeyuer.sayin.pairing

/** Shared reviewed CPace implementation; invoke only from Dispatchers.IO. */
object NativePairing {
    init { System.loadLibrary("voicelink_pairing") }
    external fun pairCode(address: String, port: Int, code: String): String
    /** Reads only the three public, read-only product market-name properties. */
    external fun readDeviceMarketName(key: String): String
}
