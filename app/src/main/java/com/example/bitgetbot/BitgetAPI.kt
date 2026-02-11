package com.example.bitgetbot

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

// --- MODELOS DE DATOS ---
data class BitgetResponse(val code: String, val msg: String, val data: List<TickerData>)
data class TickerData(val symbol: String, val lastPr: String, val bidPr: String, val askPr: String)

data class CandleResponse(val code: String, val msg: String, val data: List<List<String>>)

// NUEVO: Respuesta de la Cuenta (Saldo)
data class AccountResponse(val code: String, val msg: String, val data: List<AccountData>)
data class AccountData(
    val marginCoin: String, // Ejemplo: "USDT"
    val available: String,  // Saldo disponible
    val equity: String      // Saldo total (con PnL no realizado)
)

// --- INTERFAZ ---
interface BitgetService {
    // 1. Público: Precio
    @GET("/api/v2/mix/market/ticker")
    fun getTicker(@Query("symbol") s: String, @Query("productType") p: String): Call<BitgetResponse>

    // 2. Público: Velas
    @GET("/api/v2/mix/market/candles")
    fun getCandles(@Query("symbol") s: String, @Query("productType") p: String, @Query("granularity") g: String, @Query("limit") l: Int): Call<CandleResponse>

    // 3. PRIVADO: Saldo (Requiere Firma)
    @GET("/api/v2/mix/account/accounts")
    fun getAccount(
        @Header("ACCESS-KEY") apiKey: String,
        @Header("ACCESS-SIGN") signature: String,
        @Header("ACCESS-PASSPHRASE") passphrase: String,
        @Header("ACCESS-TIMESTAMP") timestamp: String,
        @Query("productType") productType: String = "USDT-FUTURES"
    ): Call<AccountResponse>
}

object BitgetClient {
    private const val BASE_URL = "https://api.bitget.com"
    val api: BitgetService by lazy {
        Retrofit.Builder().baseUrl(BASE_URL).addConverterFactory(GsonConverterFactory.create()).build().create(BitgetService::class.java)
    }
}