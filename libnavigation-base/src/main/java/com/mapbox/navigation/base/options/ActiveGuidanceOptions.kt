package com.mapbox.navigation.base.options

class ActiveGuidanceOptions private constructor(){

    /**
     * @return builder matching the one used to create this instance
     */
    fun toBuilder(): Builder = Builder()

    /**
     * Build a new [ActiveGuidanceOptions]
     */
    class Builder {

        /**
         * Build the [ActiveGuidanceOptions]
         */
        fun build() = ActiveGuidanceOptions(

        )
    }

    /**
     * Regenerate whenever a change is made
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    /**
     * Regenerate whenever a change is made
     */
    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    /**
     * Regenerate whenever a change is made
     */
    override fun toString(): String {
        return "ActiveGuidanceOptions()"
    }
}
