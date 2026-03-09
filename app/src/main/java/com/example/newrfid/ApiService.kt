package com.example.newrfid

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET

interface ApiService {
    @POST("api/rfid/tags")
    suspend fun pushTags(@Body body: PushTagsRequest): Response<PushTagsResponse>

    @GET("api/master/products")
    suspend fun getMasterProducts(): Response<MasterProductsResponse>

    @GET("api/rfid/mapping")
    suspend fun getRfidMapping(): Response<RfidMappingResponse>
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