package com.example.newrfid

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @POST("api/rfid/tags")
    suspend fun pushTags(
        @Body body: PushTagsRequest
    ): Response<PushTagsResponse>

    @GET("api/master/products")
    suspend fun getMasterProducts(): Response<MasterProductsResponse>

    @GET("api/rfid/mapping")
    suspend fun getRfidMapping(): Response<RfidMappingResponse>

    @POST("api/rfid/scan-sessions")
    suspend fun saveScanSession(
        @Body body: SaveScanSessionRequest
    ): Response<SaveScanSessionResponse>

    @GET("api/rfid/unregistered-tags")
    suspend fun getUnregisteredTags(): Response<UnregisteredTagsResponse>

    @POST("api/rfid/register")
    suspend fun registerTags(
        @Body body: RegisterTagRequest
    ): Response<RegisterTagResponse>
}

data class MasterProductsResponse(
    val success: Boolean,
    val data: List<MasterProduct>
)

data class MasterProduct(
    val itemcode: String,
    val itemname: String,
    val endstock: Int?
)

data class RfidMappingResponse(
    val success: Boolean,
    val data: List<RfidMapItem>
)

data class RfidMapItem(
    val epc: String,
    val item_code: String
)