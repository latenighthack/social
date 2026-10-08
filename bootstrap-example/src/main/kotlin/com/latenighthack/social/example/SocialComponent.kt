package com.latenighthack.social.example

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktstore.Database
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.connector.LockKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountProviders
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.ProfilesProviders
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.domain.RoomsKeySource
import com.latenighthack.social.rooms.domain.RoomsProviders
import com.latenighthack.social.messages.domain.MessagesProviders
import com.latenighthack.social.contacts.domain.ContactsProviders
import com.latenighthack.social.typing.domain.TypingProviders
import com.latenighthack.social.readreceipts.domain.ReadReceiptsProviders
import com.latenighthack.social.remotecontent.domain.RemoteContentProviders
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import com.latenighthack.social.runtime.SocialTaskScope
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/** The executable example linked from docs/di.md; only the host owns the database. */
@SocialScope
@Component
abstract class SocialComponent(
    @get:Provides val keyValueStore: KeyValueStore,
    @get:Provides val database: Database,
    @get:Provides val rpcClient: RpcClient,
    @get:Provides val httpClient: HttpClient,
) : AccountProviders, ProfilesProviders, RoomsProviders, MessagesProviders,
    ContactsProviders, TypingProviders, ReadReceiptsProviders, RemoteContentProviders {
    @Provides
    fun lockKeySource(rooms: RoomsKeySource): LockKeySource = rooms

    abstract val account: AccountManager
    abstract val profiles: MyProfilesManager
    abstract val rooms: RoomsManager
    abstract val lifecycles: Set<DomainLifecycle>
    abstract val tasks: SocialTaskScope
    abstract val lockKeySource: LockKeySource
    abstract val authenticationKeySource: com.latenighthack.lockers.connector.AuthenticationKeySource
}
