// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.util.HexFormat
import java.util.UUID

internal val testKdf = KdfParameters(iterations = 1)
internal fun credentials(password: String = "test-only master 🗝 ä", key: ByteArray? = null): Credentials =
    Secret(password.toCharArray()).use { Credentials(it, key) }
internal fun field(value: String, hidden: Boolean = true) = Field(Secret(value.toCharArray()), hidden)
internal fun id(): String = UUID.randomUUID().toString()
internal const val DATE = "2026-09-23T12:00:00Z"

internal fun sampleVault(): Vault {
    val customer = Customer(id(), "Customer-SENTINEL-4531", contactName = "Contact-SENTINEL-2291",
        contactEmail = "contact@example.invalid", phone = "+49 30 1234567", website = "https://customer.example.invalid",
        notes = Secret("Customer-Notes-SENTINEL-6612".toCharArray()))
    val project = Project(id(), "Project-SENTINEL-8421", customer.id, description = "Description-SENTINEL-1182",
        notes = Secret("Project-Notes-SENTINEL-7741".toCharArray()))
    val serverId = id()
    val webId = id()
    val data = listOf(
        EntryData.Web(field("https://example.invalid/login", false), field("User-SENTINEL-3412"),
            field("Password-SENTINEL-7751"), field("JBSWY3DPEHPK3PXP")),
        EntryData.Transfer(field("files.example.invalid"), 22, TransferProtocol.SFTP, field("transfer"), field("transfer-secret"), field("/")),
        EntryData.Email(field("mail@example.invalid"), field("mail"), field("mail-secret"),
            imap = MailEndpoint(field("imap.example.invalid"), 993, MailEncryption.TLS),
            smtp = MailEndpoint(field("smtp.example.invalid"), 587, MailEncryption.STARTTLS)),
        EntryData.Panel(field("https://panel.example.invalid"), field("panel"), field("panel-secret"), field("admin")),
        EntryData.Server(field("server.example.invalid"), 22, field("root"), field("server-secret"), field("Linux"), field("root")),
        EntryData.Ssh(SshKeyType.ED25519, field("Private-Key-SENTINEL-5531"), field("public-key"), field("key-passphrase"),
            field("fingerprint"), listOf(serverId)),
        EntryData.Domain(field("example.invalid"), field("registrar"), field("DNS notes"), webId),
        EntryData.Custom(mapOf("custom" to field("Custom-SENTINEL-3951", false))),
    )
    val entries = data.mapIndexed { index, value ->
        Entry(if (index == 0) webId else if (index == 4) serverId else id(), "Title-SENTINEL-$index", value, DATE, DATE,
            customer.id, project.id, listOf("Tag-SENTINEL-9891"), Secret("Notes-SENTINEL-7123".toCharArray()),
            expiresOn = "2027-01-01", deletedAt = if (index == 7) DATE else null,
            history = if (index == 0) listOf(HistoryItem(DATE, EntryData.Custom(mapOf("password" to field("old-password"))))) else emptyList(),
            pinned = index == 1)
    }
    val template = EntryTemplate.of(id(), "Template-SENTINEL-3310", entries[0])
    return Vault(customers = listOf(customer), projects = listOf(project), entries = entries, templates = listOf(template))
}

/** Fixed format regression vector: sequential salt/nonce, one Argon2 iteration, JDK AES-GCM. Password `fixture-password`. */
internal fun frozenV1Fixture(): ByteArray = HexFormat.of().parseHex(
    "4b4559524f4f4b000001004c0000010100000013000100000000000100000004" +
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b" +
    "e833639db333bb90708d90bf2938a8b1f8fe233c792159ea8410173a7edec06c34a5eb216675ec684d9f80e1f5fad0979741aa81cb8981d9ca8c9d657fb8a016c2cf5083616eb74f1000ff9eac3ab4ac10e5eb222b3d4738158133c76d50fe0fef503e825d1578599772e55a0c66e01bfb9dd642eb1f05b932f81728aeca814aefe2867f84a3")

/**
 * Frozen schema 2 vector as written by 0.8.0: one Argon2 iteration with key file, fixed salt `80..9f` and nonce
 * `a0..ab`, encrypted independently with JDK AES-GCM. Password `fixture-password-v2`, key file [FROZEN_V2_KEY_FILE].
 * Vault `22222222-3333-4444-8555-666666666666` at revision 3 with two customers (one with every contact field and
 * notes), a project with description and notes, a pinned web login with TOTP, history and expiry, a server entry, a
 * pinned custom entry in the trash and two templates. `CodecTest` checks the complete decoded content.
 */
internal fun frozenV2Fixture(): ByteArray = HexFormat.of().parseHex(
    "4b4559524f4f4b000001004c0001010100000013000100000000000100000004" +
    "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaab" +
    "585d7710aba663df773e437b9d43f24d4fe225314ec6137005da21f8e0c6eeefc1c19c745a6ebe98827ed1c7420dd83979064a5cb8135fc31987b1d6" +
    "f11b235958aa290c3e05452c5f857c8c032ab838ab747567b42c67190cd66342cf760753edddc210c35dde6fb56634b427d437e7513b50b32c6a7184" +
    "e87b7a3c043fcbc6cb79562761b738c36dd96653aa8dff042ff76ed4040c98ec208bc65c00567d9db3af2a6319cbb68fb7d9405f53e74019e8352358" +
    "95bb46023791c07f0b0c7a635569cc0bfb85d1cbfcb17de345eab54cce7b9a4f230ddf6080b1ad5ca507792785ebfe672cd3fc3d167634869555bf45" +
    "48d52c65521afd4de3cd326b84c6f140924fa57c8f6c28d522f926c66a11bff0f6e1aa4362e5f297211bb3140131dd4a28e1cd3b514f3439fe07db33" +
    "ff9a7a9f5673ab8fb3ec7e3bc0ae3d03111b1dbfe0c9383e8af6e91b9950d0dfe69ed3e950c83224c14daf508411a3e14141d33fbaae45b4a384ac6d" +
    "cb3dda9e9954505d4464f3eb7c5147034a7366be637f190523ed68ef6789a227f089c8659e0fa109d2be2d3a16a80550dddf086d2735a1a12a61d8fb" +
    "4f4b5ec691fe2c7fba5c0b4dae990285e9949bced2ed55e05ed946ab834f63384f765125b7d1810a054a5878436ad27141b93be26c5b89744e473552" +
    "ce02072308dbcc035c2db17a865a14926c80b766578648556201f40433bb80f4cf9976560d999e9f9ea59abbe3fe4b332fe84712510f68f80b0aadff" +
    "a6cfb39632bbac07d30caea9bf17cd5dc4f3d4cf86f8c1c29a7ce36e408c5da18330bd9e2e1fee226008b312c5c42b6d7ea8954da037e919adc1cd1c" +
    "e8cfb922ffa6bcb25a261827d05f771cc42f4ffb598b3c86c7976484869e95f896680fac08154f8dd8c70be083dd4d10f6f4a136156bccd8a7593e26" +
    "ede0072633fb8e4907f95d4320c34c4a528aa5c6e86e1c9beb61ddbc14619062202825e4c894141f1ed57104272f5654df2ad994c528ce72a2225a79" +
    "1d891d3112b53f7990084012c76e552a82283afa9cf7271f24fc521e4cbcdf579e7e43171b3a2ae91d784ac72bf10afd0fe2bafbbe8a2736092ded84" +
    "e346e49cec18f0f49162f95c45424e469eb40c8e7ab0fd0a4493f66f016c3577279a76617fb1ea26a8a1cb046ed3d66b3650770c3a9e5169a598774f" +
    "1a2213461c3f1fcda90e50ac0b62dbfd9f6b1b49d091b0f55926dbcd551341505c4687997c9094475d714fcf46d16178c74b72f3c64deefc8332ca51" +
    "5fe3b171f810611cc057103760042e208d93cc48e35c6222a8c5dcccc6d0538cf4c9e7445495a99675c42a97b38616f63ad0cf1f9825d2ae8b4ed927" +
    "cebbecf3da9baa838cdfdb90d05a31a60740bbc790792a837cdbcc09fa93bb11bae0d60d513ab908f911f9bb284802f09f712ab01a905bd103323632" +
    "1f851ace26b65201ab28642049a1200cf558f2c5bb2501922759b4672c7bb4206e898ec372f91ba34096ee0fe1925bdb436c397195257b240be6340c" +
    "3ad2cf99fb3153e963b1fe2132b4bc81c0f0bcc8d87c3ae28ae27253767a70ca3232c216d4a16105be6c81f4e5df6d3bfe8e29e0689ed87e54e89a49" +
    "9281a6b2104675ef4a14cd69f6022d595f2901a3637b98b33c9e17816d4da5d47fc2b171c9d68bca81fb81ba75eb3947be42b31442f043e39c2a979d" +
    "f16b149c0c8390d137d2948d21b3964ff9e802954d805d868dced59ec88e81caf7e6d8359b6e44a2dea875f59268f9e97315dcc09fc7faf85762390d" +
    "ce1ebecf9ab84abd311235c6bf517d622c336278d5e2d3f7e2186eb6a02f259b036b80bc6085f84f66c1896db5745cc235d40771be999e565561a8ee" +
    "52a8b9c6729cfe4824a122823d510c7ad8f98179af8f03ce24a0547ad71f036d06b8a1b750e83956eac80927a10e3d86adf4644b4cace07d731e1ebf" +
    "d1ec60206100ff3c88e9634bd30d6d40941dee1493172baf4ab36ab9bc6c93e4d0110711ccd8ca0aac320f3ccdf90ec0ec83523c885d9abc5ce48fb7" +
    "aa86769b73c2a8fa0f82df9b7545d71673fe85ae8b3bb0612fecfd6dd3eda6708103b929b3badc4d1b58913deef1b10766ee671b09c03e4bb901d93f" +
    "5af0c806c7e35d3c8db44cf35c981ac9ba5531c633fc4b35d9a1881a85b6f5e7930a0d401faa51d5fe6804ad1a388acb6fe81d8c58c1e9563ba34711" +
    "cb2133617b976a7465ac2390cc5cc83f9bab65835ef0addf4f0920311955f86313f2ec5568f736b2a9857f83b56c339c7537c3024eb2ea0c14454c0c" +
    "c62250927ff2dfcebf0592d953a11047f504ae6587eb084e715ac7db890b7acf6aa3fdb5fab52f7656250b38b521eb42a3378bd3edffff1f9b4380d4" +
    "9f62f6dbec043715d928f4a9bfa4b09234b3b53d7b05659ae35c59b7bb710f01ac128c30a1b351d3ebac4d14fa974ce7a08855a6c51f19d21b1b2dfe" +
    "fa1647b97e7df24212fd7ec1bbd2a771ee42e5ab68e5f28257dc45bd735ce3a55ec60db4ebd5207d97bf456be24ac1abdbcda426578d0fc9033f7704" +
    "60f7ebb62a724fbeb94f68cdc5320780792bbfccee0c4283752512232efc2378c24bc05a582db07177afa39bebca69e4930ef227b12b3fc049fdd482" +
    "88f456f7dae3385db05646830f3053b3dfb316485b9ff2b606316b5666f2164edf32a1f21d5e5a25e5b018e9089f6b36e4f62d4818b4d8f069e1fbbe" +
    "41c7d382794edf4b71a644b1eab9a9bf1d9d979d9a00bc177a2847f5a1223539b6c0fd63f7c15ed191355f9524599be0069e1066e925010ae613ecb2" +
    "07c98b00643be2f891039f7583fe41d2742a5abef486b1ab5a4f78ac85293fa876e098881f61c8e9a7575a13a9418b93b6156d43b1e9b87f0d539095" +
    "f82de1fc49c33aaef1281b23a6c9170b0dcf03a0d9120758edce86a74edf155c13fa679797ff48bd72816ea109c7950570365135322b22dadfd5f366" +
    "bbe214de215255346a62908f27dbc92c6e3ab7e231d49c25016a8f918b5322b05632706f514ca99cbc261b5e28b50dd10155719be741362b2246d3fd" +
    "6852c642756a48e4b6c07a87c87f6a1f4aa8e781eca0d310ee5378bd87799b1aca40f7e6e9392fe17df942be7af84e03dbc4ce4f8ab1cdca62a070a4" +
    "bc3257afc55f9e7f1077a0b8780dc7a608bee20a440981ed56cff9a983f9fc4c448823f1ae7afc9c0101970aa68479be8a4ab5977b64730af9ebf6a8" +
    "bebe960b45e59fa83df4e7ff3f6b0aab4e0d7ecc16c6660b1a67e6c3fdaff29cf3407e939ecd3c3688086a3ee5ac7a0e5b4f5457b2ab5bb5b4c6c455" +
    "ab808e055880a679065fc8acee3584c1b2ebf4be1bcbd1c71dae706b80682eb5dc64a6c9d22d85e9c6be641b25265809685f3ea695e94010a16dac77" +
    "8a3dc6bec4a6d8a4b4bba3c847925c50768dea1c279c55ded3d5bbbaa8bccee9dc58a0d2fcc8a373b110c9b22f528aec978a4d4f274f964f7c3ff6f6" +
    "c77cb11837b703aceaeda7d399a900d5d0ec478ed4d2187762699883ab289a82a65c2d4c23ee5aefda32702198e4b03160aeb763c31023f78c0cda4f" +
    "9ceb94cb7907252816ed92ff3c4bbc5680b29fdd2abd3c5b77265c9a7e6c476f06b461ce021ac411af33e1f0ae24a93198339ed497602f4749e585ea" +
    "a4333250a11d68f12cd2d02f250b34e0e8e1692fd025a4eebe24af8d1a96e5882ee80acaaffbec5a1adca5883163950e14e2630fa3cf06ded6ed4f8a" +
    "5e1ee884c34567289f71d38bdbebbd23cda5bcf42ea01e0c8840e176cb62f16b6aea2cda1dadd4ae6a2ecaa81fd9168f5473490d515b35ea1684e50b" +
    "3a349d4e89845252bc523f65d1c2381c489dd54fd24c4c15d2c1a40e77f0473e7c7013c649dd8c81e2929f6100bb684e5d7db5fffb5589791aa850c2" +
    "eb09ac1f3ed319746d0b3da1069ea839de32a37aa9b2d3f9f18454833dcc9f42bb12199060979eb20f28216d61832a056d3d261048a783e092094675" +
    "0a79cf9acd901b94085c595c1bb812fa7a550bf08a32f57778e6bbd9cfbd41942562c3a34edb56a7a89f2eb7a0d48912aa7da20a3d70cb82929ca2ab" +
    "594bf993925c4d7d58cd38c0a6803018ec24ab1f3e19c3cea7dc387bbc333e9c296379f5e893a8cd93163c9ba2fb16a7e466ef258c40b065a967963c" +
    "e052b1fced5978a88bf8bbf9ba4badcf6e1ae1c58ba99bef42bc2b97056a887a780c7c9f031f7eca7601b73a76431e61f03f60be75f3c70176abbbb3" +
    "6d6eb778164069e27167f44076735539e17f17ed0223c53bbf2c50b3bc55747ddd28bcee7f6835814af812b41100b382d7d2e77cd7f765990b9a6a16" +
    "122faa05d5029208b5d45d36912158a5b4995bffad75ae031850d765cde89cfa4f4faf9d713941fa4e2d13262a65303ad1eda2e9cb6f390b7360158c" +
    "39dd4eb60a9a2f223aab13ba9adf553ab5104cc89a958b1b72262032d83fba1a3528e4349200ba8ecdba2ec348092001b1feedfecec2af0a6762412c" +
    "9c8414b5e54af009d6d8757e87f0b91f053ae321c2d53b71c347b44c010aaf1babbc67d3c0a4867afad3257350d3e6aa474feb788920789ff231e869" +
    "159fdede512cbf419079bbea2d9734f2150a8718a544480870aa6eb0e8e8bef018c3d0d3322ceb3ae1bcb12be2ce22c3cfc3ac5ff3314ed4137aa383" +
    "59dc352b2e85985095f9c82218ea13893b5d174a3ea69cdd628de4f3abcbe270591f94f264cc7bf8a430600228552d8e5b06bf6c4677ba218cfd311f" +
    "7db4306050664461f12d3afe6e7f355c6d092a680c998621961c4768e4aeda3a29be5672500a6d49d1867f701672f1bcda225441690b")

/** The 32-byte key file of [frozenV2Fixture]: bytes `40..5f`. */
internal val FROZEN_V2_KEY_FILE: ByteArray get() = ByteArray(32) { (0x40 + it).toByte() }
