package com.latenighthack.social.runtime

import me.tatarka.inject.annotations.Scope

/**
 * The object-graph scope shared by every social feature manager and key source. A component
 * annotated with this scope holds a single instance of each scoped binding, so the manager the
 * use cases call, the manager the key source forwards to, and the manager the app starts are all
 * the same object.
 */
@Scope
annotation class SocialScope
