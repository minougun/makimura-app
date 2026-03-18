package com.minou.pedometer

enum class RamenMenuCategory(val label: String) {
    RAMEN("ラーメン"),
    TOPPING("トッピング"),
    RICE("ご飯・サイド"),
    DRINK("ドリンク"),
}

data class RamenMenuCatalogItem(
    val id: String,
    val name: String,
    val priceYen: Int,
    val category: RamenMenuCategory,
    val required: Boolean = false,
)

object RamenMenuCatalog {
    val items: List<RamenMenuCatalogItem> = listOf(
        RamenMenuCatalogItem(
            id = "ramen",
            name = "ラーメン",
            priceYen = 600,
            category = RamenMenuCategory.RAMEN,
            required = true,
        ),
        RamenMenuCatalogItem("negi", "ねぎ", 120, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("garlic", "ニンニク", 100, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("corn", "コーン", 100, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("boiled_egg", "煮卵", 150, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("menma", "メンマ", 150, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("kimchi", "キムチ", 150, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("natto", "納豆", 150, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("chashu_1", "チャーシュー1枚", 120, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("chashu_2", "チャーシュー2枚", 240, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("chashu_3", "チャーシュー3枚", 360, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("extra_noodles", "替玉", 170, RamenMenuCategory.TOPPING),
        RamenMenuCatalogItem("rice_plain", "ご飯", 200, RamenMenuCategory.RICE),
        RamenMenuCatalogItem("rice_mini_chashu", "ミニチャーシュー丼", 370, RamenMenuCategory.RICE),
        RamenMenuCatalogItem("rice_mentaiko", "明太子ご飯", 370, RamenMenuCategory.RICE),
        RamenMenuCatalogItem("beer", "瓶ビール", 650, RamenMenuCategory.DRINK),
        RamenMenuCatalogItem("chuhai_plain", "プレーンチューハイ", 500, RamenMenuCategory.DRINK),
        RamenMenuCatalogItem("shochu_mugi", "麦焼酎", 500, RamenMenuCategory.DRINK),
        RamenMenuCatalogItem("shochu_imo", "芋焼酎", 500, RamenMenuCategory.DRINK),
        RamenMenuCatalogItem("kaku_highball", "角ハイボール", 550, RamenMenuCategory.DRINK),
    )

    val priceTable: Map<String, Int> = items.associate { it.name to it.priceYen }
    val requiredItemNames: Set<String> = items.filter { it.required }.mapTo(linkedSetOf()) { it.name }
    val toppingItemNames: Set<String> = items
        .filter { item -> item.category == RamenMenuCategory.TOPPING }
        .mapTo(linkedSetOf()) { item -> item.name }
    val chashuItemNames: Set<String> = items
        .filter { item -> item.id.startsWith("chashu_") }
        .mapTo(linkedSetOf()) { item -> item.name }

    fun toggleSelection(selectedNames: Set<String>, targetName: String): Set<String> {
        val normalized = selectedNames + requiredItemNames
        if (targetName in requiredItemNames) return normalized

        if (targetName in chashuItemNames) {
            val wasSelected = targetName in normalized
            val withoutChashu = normalized - chashuItemNames
            return if (wasSelected) {
                withoutChashu + requiredItemNames
            } else {
                withoutChashu + targetName + requiredItemNames
            }
        }

        return if (targetName in normalized) {
            normalized - targetName
        } else {
            normalized + targetName
        }
    }
}
