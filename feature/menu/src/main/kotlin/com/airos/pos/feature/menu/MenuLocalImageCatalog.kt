package com.airos.pos.feature.menu

import androidx.annotation.DrawableRes
import com.airos.pos.core.model.MenuItem
import java.util.Locale

private val menuImagesByCompositeKey = mapOf(
    "bar__house_lager" to R.drawable.menu_product_house_lager,
    "desserts__gelato" to R.drawable.menu_product_gelato,
    "pizzas__margherita" to R.drawable.beer_battered_mozzarella_sticks,
    "pizzas__pepperoni" to R.drawable.brooklyn_pilsner,
    "drinks__cola_0_33" to R.drawable.lonkero_ananas,
    "hot_drinks__coffee" to R.drawable.battery,
    "sides__garlic_dip" to R.drawable.bataatti_alt,
    "management__manual_discount" to R.drawable.campari_logo_1912,
)

private val menuImagesByProductName = mapOf(
    "house_lager" to R.drawable.menu_product_house_lager,
    "gelato" to R.drawable.menu_product_gelato,
    "margherita" to R.drawable.beer_battered_mozzarella_sticks,
    "pepperoni" to R.drawable.brooklyn_pilsner,
    "cola_0_33" to R.drawable.lonkero_ananas,
    "coffee" to R.drawable.battery,
    "garlic_dip" to R.drawable.bataatti_alt,
    "manual_discount" to R.drawable.campari_logo_1912,
    "sandell_s_4_7_0_33l_pullo" to R.drawable.carlsberg,
    "sandell_s_4_7_0_5l_tolkki" to R.drawable.carlsberg,
    "bacardi_breezer_lime_0_275l" to R.drawable.breezer_lime,
    "bacardi_breezer_watermelon_0_275l" to R.drawable.breezer_watermelon,
    "brooklyn_pilsner_0_33l_bottle" to R.drawable.brooklyn_pilsner,
    "lonkero_ananas_0_33l_tolkki" to R.drawable.lonkero_ananas,
    "battery_sugar_free_0_33l_tolkki" to R.drawable.battery,
    "cola_zero_0_5l_pet" to R.drawable.menu_category_drinks,
    "cappuccino_double_shot_large" to R.drawable.battery,
    "latte_vanilja_kauramaito" to R.drawable.breezer_orange,
    "gelato_pistaasi_kaksi_palloa" to R.drawable.menu_product_gelato,
    "cornetto_mansikka_suklaakastikkeella" to R.drawable.cornetto_mansikka,
    "valkosipulimajoneesi_talon_resepti" to R.drawable.menu_category_sides,
    "bataattiranskalaiset_iso_annos" to R.drawable.bataatti,
    "kana_caesar_salaatti_iso_annos" to R.drawable.menu_category_sides,
    "pepperoni_feast_family_size" to R.drawable.brooklyn_pilsner,
    "margherita_burrata_extra_basil" to R.drawable.beer_battered_mozzarella_sticks,
    "brooklyn_bbq_chicken_30_cm" to R.drawable.brooklyn_pilsner,
    "campari_spritz_0_2l_ready_to_serve" to R.drawable.campari_logo_1912,
    "captain_morgan_cola_0_33l_tolkki" to R.drawable.captain_morgan_black_40_100cl,
    "bacardi_breezer_passion_mango_0_275l" to R.drawable.breezer_passion_mango,
    "bacardi_breezer_strawberry_0_275l" to R.drawable.breezer_strawberry,
    "beer_battered_mozzarella_sticks_dip" to R.drawable.beer_battered_mozzarella_sticks,
    "bataatti_snack_bowl_talon_aioli" to R.drawable.bataatti_alt,
    "bacardi_carta_blanca_4_cl" to R.drawable.bacardi,
    "house_red_wine_16_cl_glass" to R.drawable.menu_category_bar,
    "house_white_wine_16_cl_glass" to R.drawable.menu_category_bar,
    "campari_soda_0_2l_bottle_serve" to R.drawable.campari_logo_1912,
    "captain_morgan_black_4_cl" to R.drawable.captain_morgan_black_40_100cl,
    "brooklyn_summer_ale_0_33l_bottle" to R.drawable.brooklyn_pilsner,
    "chocolate_brownie_sundae_warm_brownie" to R.drawable.menu_category_desserts,
    "orange_soda_0_5l_pet_bottle" to R.drawable.menu_category_drinks,
)

private val menuImagesByCategory = mapOf(
    "bar" to R.drawable.bacardi,
    "drinks" to R.drawable.breezer_watermelon,
    "hot_drinks" to R.drawable.breezer_orange,
    "desserts" to R.drawable.menu_category_desserts,
    "sides" to R.drawable.menu_category_sides,
    "pizzas" to R.drawable.captain_morgan_black_40_100cl,
    "management" to R.drawable.campari_logo_1912,
)

@DrawableRes
internal fun resolveLocalMenuImage(item: MenuItem): Int? {
    val categoryKey = normalizeMenuImageKey(item.category)
    val productKey = normalizeMenuImageKey(item.name)
    val compositeKey = "${categoryKey}__${productKey}"

    return menuImagesByCompositeKey[compositeKey]
        ?: menuImagesByProductName[productKey]
        ?: menuImagesByCategory[categoryKey]
}

private fun normalizeMenuImageKey(raw: String): String {
    val normalized = buildString(raw.length) {
        var previousUnderscore = false
        raw.lowercase(Locale.ROOT).forEach { character ->
            val next = when {
                character.isLetterOrDigit() -> character
                else -> '_'
            }
            if (next == '_') {
                if (!previousUnderscore) {
                    append(next)
                }
                previousUnderscore = true
            } else {
                append(next)
                previousUnderscore = false
            }
        }
    }.trim('_')

    return normalized.ifBlank { "other" }
}
