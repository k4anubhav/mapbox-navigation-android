package com.mapbox.navigation.core.trip.model.roadobject.border

/**
 * Administrative information.
 *
 * @param from origin administrative info when crossing the country border
 * @param to destination administrative info when crossing the country border
 */
class CountryBorderCrossingInfo private constructor(
    val from: CountryBorderCrossingAdminInfo,
    val to: CountryBorderCrossingAdminInfo
) {

    /**
     * Transform this object into a builder to mutate the values.
     */
    fun toBuilder(): Builder = Builder(from, to)

    /**
     * Indicates whether some other object is "equal to" this one.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CountryBorderCrossingInfo

        if (from != other.from) return false
        if (to != other.to) return false

        return true
    }

    /**
     * Returns a hash code value for the object.
     */
    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + (to.hashCode())
        return result
    }

    /**
     * Returns a string representation of the object.
     */
    override fun toString(): String {
        return "CountryBorderCrossingInfo(" +
            "from=$from, " +
            "to=$to" +
            ")"
    }

    /**
     * Use to create a new instance.
     *
     * @see CountryBorderCrossingInfo
     */
    class Builder(
        private val from: CountryBorderCrossingAdminInfo,
        private val to: CountryBorderCrossingAdminInfo
    ) {

        /**
         * Build the object instance.
         */
        fun build() = CountryBorderCrossingInfo(from, to)
    }
}
