package maestro.orchestra.filter

import maestro.Filters
import maestro.Filters.asFilter
import maestro.orchestra.ElementTrait

object TraitFilters {

    fun buildFilter(
        trait: ElementTrait,
    ): FilterWithDescription {
        return when (trait) {
            ElementTrait.TEXT -> FilterWithDescription(
                trait.description,
                Filters.hasText().asFilter()
            )
            ElementTrait.SQUARE -> FilterWithDescription(
                trait.description,
                Filters.isSquare().asFilter()
            )
            ElementTrait.LONG_TEXT -> FilterWithDescription(
                trait.description,
                Filters.hasLongText().asFilter()
            )
        }
    }

}