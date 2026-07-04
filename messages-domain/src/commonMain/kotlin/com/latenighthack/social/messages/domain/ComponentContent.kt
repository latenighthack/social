package com.latenighthack.social.messages.domain

import com.latenighthack.social.messages.v1.Component

/**
 * The first Text found in this component tree, searching depth-first through any Container children,
 * or "" when the component carries no text. Lets a renderer read a message's text without walking the
 * tree.
 */
val Component.text: String
    get() {
        fun find(component: Component): String? {
            when (val contents = component.contents) {
                is Component.OneOfContents.text -> return contents.value.text
                is Component.OneOfContents.container ->
                    for (child in contents.value.children) find(child)?.let { return it }
                else -> {}
            }
            return null
        }
        return find(this) ?: ""
    }
