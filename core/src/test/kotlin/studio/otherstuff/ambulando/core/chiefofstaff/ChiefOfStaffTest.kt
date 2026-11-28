package studio.otherstuff.ambulando.core.chiefofstaff

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.otherstuff.ambulando.core.crypto.IdentityService
import studio.otherstuff.ambulando.core.crypto.VaultSecretStore
import studio.otherstuff.ambulando.core.management.ConfirmedAutopilotOrigin
import studio.otherstuff.ambulando.core.nostr.Bech32
import studio.otherstuff.ambulando.core.nostr.Sha256Hex
import studio.otherstuff.ambulando.core.store.LocalStore
import studio.otherstuff.ambulando.core.workspaces.BoundaryGuardTransport
import studio.otherstuff.ambulando.core.workspaces.BoundaryViolation
import studio.otherstuff.ambulando.core.workspaces.WorkspaceBoundary
import studio.otherstuff.ambulando.core.transport.ChannelRecord
import studio.otherstuff.ambulando.core.transport.HttpRequest
import studio.otherstuff.ambulando.core.transport.HttpResponse
import studio.otherstuff.ambulando.core.transport.HttpTransport
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.ScopeRecord
import studio.otherstuff.ambulando.core.transport.TowerAPI
import studio.otherstuff.ambulando.core.transport.TowerClient
import studio.otherstuff.ambulando.core.transport.TowerOrigin
import studio.otherstuff.ambulando.core.transport.jsonObjectOf
import java.net.URI
import java.nio.file.Files
import java.time.Instant

class ChiefOfStaffTest {
    private val wsA = "11111111-1111-4111-8111-111111111111"
    private val wsB = "22222222-2222-4222-8222-222222222222"
    private val scopeId = "33333333-3333-4333-8333-333333333333"
    private val dmScopeId = "33333333-3333-4333-8333-333333333334"
    private val assignChannelId = "44444444-4444-4444-8444-444444444444"
    private val assignFileId = "55555555-5555-4555-8555-555555555555"
    private val cohortChannelId1 = "66666666-6666-4666-8666-666666666661"
    private val cohortChannelId2 = "66666666-6666-4666-8666-666666666662"
    private val jobFileId = "77777777-7777-4777-8777-777777777771"
    private val ontoFileId = "77777777-7777-4777-8777-777777777772"
    private val corrFileId = "77777777-7777-4777-8777-777777777773"
    private val ver1 = "88888888-8888-4888-8888-888888888881"
    private val ver2 = "88888888-8888-4888-8888-888888888882"
    private val obj1 = "99999999-9999-4999-8999-999999999991"
    private val ownerActorId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
    private val agentActorId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
    private val memberActorId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3"
    private val adminsGroupId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"
    private val workspaceGroupId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"

    private val ownerNpub = Bech32.encode("npub", ByteArray(32) { 1 })
    private val agentNpub = Bech32.encode("npub", ByteArray(32) { 2 })
    private val memberNpub = Bech32.encode("npub", ByteArray(32) { 3 })
    private val appNpub = Bech32.encode("npub", ByteArray(32) { 4 })

    private class MemoryVault : VaultSecretStore {
        private var stored: ByteArray? = null
        override fun read() = stored?.copyOf()
        override fun write(data: ByteArray) { stored = data.copyOf() }
    }

    private fun testIdentity(seed: Byte): IdentityService {
        val root = Files.createTempDirectory("cos-id").toFile()
        val id = IdentityService(java.io.File(root, "vault"), MemoryVault())
        runBlocking { id.importIdentity(Bech32.encode("nsec", ByteArray(32) { seed }), "123456") }
        return id
    }

    @Test fun envelopeRoundTripAndEncryptedRejection() {
        val payload = buildJsonObject { put("b", 2); put("a", 1) }
        val bytes = ChiefOfStaffEnvelope.encode(payload)
        assertEquals(
            """{"encryption":{"mode":"none"},"envelope_version":1,"payload":{"a":1,"b":2}}""",
            bytes.decodeToString(),
        )
        assertEquals(payload, ChiefOfStaffEnvelope.decode(bytes))

        val encryptedBytes = """{"envelope_version":1,"encryption":{"mode":"nip44-v2"},"payload":{"ciphertext":"abc"}}""".toByteArray()
        try {
            ChiefOfStaffEnvelope.decode(encryptedBytes)
            fail("Encrypted envelope without adapter must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nip44-v2"))
        }
    }

    @Test fun assignmentValidationRejectsWorkspaceAndAppMismatches() {
        val a = ChiefOfStaffAssignment(
            workspaceId = wsA,
            workspaceOwnerNpub = ownerNpub,
            appNpub = appNpub,
            assignmentRevision = 2,
            assignedActorId = agentActorId,
            assignedNpub = agentNpub,
            assignedDisplayName = "Rick",
            previousAssignedNpub = null,
            updatedByActorId = ownerActorId,
            updatedByNpub = ownerNpub,
            updatedAt = "2026-11-28T00:00:00Z",
        )
        val parsed = ChiefOfStaffAssignment.parse(a.toJson(), ExpectedWorkspaceContext(wsA, ownerNpub, appNpub))
        assertEquals(a, parsed)
        assertEquals(2, parsed.assignmentEpoch)

        try {
            ChiefOfStaffAssignment.parse(a.toJson(), ExpectedWorkspaceContext(wsB, ownerNpub, appNpub))
            fail("Cross-workspace assignment must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("workspace_id"))
        }
    }

    @Test fun assignmentAclEnforcesManagerOnlyWrites() {
        val members = listOf(
            ChiefOfStaffAcl.MemberRoleEntry(ownerActorId, ownerNpub, "owner"),
            ChiefOfStaffAcl.MemberRoleEntry(memberActorId, memberNpub, "member"),
        )
        val groups = listOf(
            ChiefOfStaffAcl.GroupEntry(adminsGroupId, "system", "Admins", effectiveMemberIds = setOf(ownerActorId)),
            ChiefOfStaffAcl.GroupEntry(workspaceGroupId, "system", "Workspace"),
        )
        val validGrants = listOf(
            grant("group", adminsGroupId, listOf("channel.read", "file.read", "file.write", "channel.manage", "channel.grants.manage")),
            grant("group", workspaceGroupId, listOf("channel.read", "file.read")),
        )
        val (hasWriter, hasReader) = ChiefOfStaffAcl.verifyAssignmentChannelGrants(validGrants, members, groups, ownerNpub)
        assertTrue(hasWriter)
        assertTrue(hasReader)

        // Workspace group holding file.write must fail
        val badWorkspaceWrite = listOf(
            grant("group", adminsGroupId, listOf("channel.read", "file.read", "file.write")),
            grant("group", workspaceGroupId, listOf("channel.read", "file.read", "file.write")),
        )
        assertThrowsIllegal { ChiefOfStaffAcl.verifyAssignmentChannelGrants(badWorkspaceWrite, members, groups, ownerNpub) }

        // Non-manager actor holding file.write must fail
        val badMemberWrite = listOf(
            grant("group", adminsGroupId, listOf("channel.read", "file.read", "file.write")),
            grant("actor", memberActorId, listOf("channel.read", "file.read", "file.write")),
        )
        assertThrowsIllegal { ChiefOfStaffAcl.verifyAssignmentChannelGrants(badMemberWrite, members, groups, ownerNpub) }

        // A writer group is verified structurally, never by its name: no effective members, or any non-manager, fails.
        for (writerGroup in listOf(
            ChiefOfStaffAcl.GroupEntry(adminsGroupId, "system", "Admins"),
            ChiefOfStaffAcl.GroupEntry(adminsGroupId, "system", "Admins", effectiveMemberIds = setOf(ownerActorId, memberActorId)),
        )) {
            assertThrowsIllegal {
                ChiefOfStaffAcl.verifyAssignmentChannelGrants(validGrants, members, listOf(writerGroup, groups[1]), ownerNpub)
            }
        }
        // The caller's own server-verified workspace.manage (from /me) counts as a verified manager member.
        val delegatedAdmins = ChiefOfStaffAcl.GroupEntry(adminsGroupId, "system", "Admins", effectiveMemberIds = setOf(ownerActorId, agentActorId))
        assertThrowsIllegal {
            ChiefOfStaffAcl.verifyAssignmentChannelGrants(validGrants, members, listOf(delegatedAdmins, groups[1]), ownerNpub)
        }
        ChiefOfStaffAcl.verifyAssignmentChannelGrants(validGrants, members, listOf(delegatedAdmins, groups[1]), ownerNpub, setOf(agentActorId))
    }

    @Test fun currentManagerAgentPassesProvenanceOnlyViaServerAuthorityBoolean() {
        val members = listOf(
            ChiefOfStaffAcl.MemberRoleEntry(ownerActorId, ownerNpub, "owner"),
            // Current-manager agent: membership.role=agent, server authority.can_manage_workspace=true
            ChiefOfStaffAcl.MemberRoleEntry(agentActorId, agentNpub, "agent", canManageWorkspace = true),
            // Labels and self-asserted permission arrays are not authority
            ChiefOfStaffAcl.MemberRoleEntry(memberActorId, memberNpub, "admin", setOf("workspace.manage")),
        )
        val channelRaw = buildJsonObject {
            put("created_by_actor_id", agentActorId)
            put("created_by_actor_npub", agentNpub)
        }
        val fileRaw = buildJsonObject {
            put("created_by_actor_id", agentActorId)
            put("created_by_actor_npub", agentNpub)
            put("current_version", buildJsonObject {
                put("created_by_actor_id", agentActorId)
                put("created_by_actor_npub", agentNpub)
            })
        }
        // Effective-manager agent passes provenance verification
        ChiefOfStaffAcl.verifyManagerProvenance(channelRaw, fileRaw, members, ownerNpub, agentActorId, agentNpub)

        // role=admin label + self-asserted workspace.manage WITHOUT the server boolean must fail closed
        val unprivilegedChannel = buildJsonObject {
            put("created_by_actor_id", memberActorId)
            put("created_by_actor_npub", memberNpub)
        }
        val unprivilegedFile = buildJsonObject {
            put("created_by_actor_id", memberActorId)
            put("created_by_actor_npub", memberNpub)
            put("current_version", buildJsonObject {
                put("created_by_actor_id", memberActorId)
                put("created_by_actor_npub", memberNpub)
            })
        }
        assertThrowsIllegal {
            ChiefOfStaffAcl.verifyManagerProvenance(unprivilegedChannel, unprivilegedFile, members, ownerNpub, memberActorId, memberNpub)
        }
    }

    @Test fun ordinaryMemberVerifiesDelegatedAgentManagerViaServerAuthorityBoolean() = runBlocking {
        // Member-only /members fixture: ONLY {actor, authority: {can_manage_workspace}} with NO membership, /groups=403, /grants=403
        val agentAuthoredAssignment = ChiefOfStaffAssignment(
            workspaceId = wsA,
            workspaceOwnerNpub = ownerNpub,
            appNpub = appNpub,
            assignmentRevision = 1,
            assignedActorId = agentActorId,
            assignedNpub = agentNpub,
            assignedDisplayName = "Agent Manager",
            previousAssignedNpub = null,
            updatedByActorId = agentActorId,
            updatedByNpub = agentNpub,
            updatedAt = "2026-11-28T00:00:00Z",
        )
        val bytes = ChiefOfStaffEnvelope.encode(agentAuthoredAssignment.toJson())
        val sha = Sha256Hex.of(bytes)
        var agentCanManage: kotlinx.serialization.json.JsonElement? = JsonPrimitive(true)
        var agentMembershipRole: String? = null

        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                if (path.endsWith("/groups") || path.endsWith("/grants")) {
                    return HttpResponse(403, """{"code":"permission_denied"}""".toByteArray())
                }
                return when {
                    path.endsWith("/members") -> jsonResp(buildJsonObject {
                        put("members", buildJsonArray {
                            // Owner entry: no membership field, authority=true
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", ownerActorId); put("npub", ownerNpub); put("kind", "human") })
                                put("authority", buildJsonObject { put("can_manage_workspace", true) })
                            })
                            // Delegated agent manager: no membership field, server authority boolean controls trust
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", agentActorId); put("npub", agentNpub); put("kind", "agent") })
                                agentCanManage?.let { v -> put("authority", buildJsonObject { put("can_manage_workspace", v) }) }
                                agentMembershipRole?.let { r -> put("membership", buildJsonObject { put("role", r) }) }
                            })
                            // Ordinary viewer: no membership field, can_manage_workspace=false
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", memberActorId); put("npub", memberNpub); put("kind", "human") })
                                put("authority", buildJsonObject { put("can_manage_workspace", false) })
                            })
                        })
                    })
                    path.endsWith("/scopes") -> jsonResp(buildJsonObject {
                        put("scopes", buildJsonArray { add(buildJsonObject { put("id", scopeId); put("workspace_id", wsA); put("name", "Home"); put("kind", "scope") }) })
                    })
                    path.endsWith("/scopes/$scopeId/channels") -> jsonResp(buildJsonObject {
                        put("channels", buildJsonArray {
                            // Live Tower omits created_by_actor_id on channels
                            add(buildJsonObject {
                                put("id", assignChannelId); put("scope_id", scopeId); put("workspace_id", wsA)
                                put("name", "Chief of Staff"); put("kind", "channel")
                                put("metadata", ChiefOfStaffContract.assignmentChannelMetadata(wsA, appNpub))
                            })
                        })
                    })
                    path.endsWith("/channels/$assignChannelId/files") -> jsonResp(buildJsonObject {
                        put("files", buildJsonArray {
                            add(fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, agentActorId, agentNpub))
                        })
                    })
                    path.endsWith("/files/$assignFileId") -> jsonResp(buildJsonObject {
                        put("file", fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, agentActorId, agentNpub))
                    })
                    path.endsWith("/files/$assignFileId/versions/$ver1/object") -> HttpResponse(200, bytes)
                    else -> HttpResponse(404, "{}".toByteArray())
                }
            }
        }

        val api = TowerAPI(TowerClient(TowerOrigin("https://tower.invalid"), transport, testIdentity(3)))
        // 1. Server authority.can_manage_workspace = true -> succeeds without /groups or /grants
        val ok = api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
        assertTrue(ok is AssignmentLoadResult.Ready)
        assertEquals(agentNpub, (ok as AssignmentLoadResult.Ready).assignment.assignedNpub)

        // 2. Server authority.can_manage_workspace = false -> fails closed
        agentCanManage = JsonPrimitive(false)
        try {
            api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
            fail("can_manage_workspace=false must fail closed")
        } catch (_: ChiefOfStaffError.Integrity) {}

        // 3. Missing authority -> fails closed
        agentCanManage = null
        try {
            api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
            fail("Missing authority must fail closed")
        } catch (_: ChiefOfStaffError.Integrity) {}

        // 4. String "true" instead of JSON boolean -> fails closed
        agentCanManage = JsonPrimitive("true")
        try {
            api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
            fail("Non-boolean string 'true' must fail closed")
        } catch (_: ChiefOfStaffError.Integrity) {}

        // 5. Older server (no authority) showing a manager-view role label: owner/admin/agent labels never verify
        agentCanManage = null
        for (label in listOf("admin", "owner", "agent")) {
            agentMembershipRole = label
            try {
                api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
                fail("membership.role=$label without authority must fail closed")
            } catch (_: ChiefOfStaffError.Integrity) {}
        }
    }

    @Test fun privateCohortAclRequiresStrictlyOwnerAndAgent() {
        val members = listOf(
            MemberRecord(wsA, ownerActorId, ownerNpub, "human", "Pete"),
            MemberRecord(wsA, agentActorId, agentNpub, "agent", "Rick"),
            MemberRecord(wsA, memberActorId, memberNpub, "human", "Other"),
        )
        val valid = listOf(
            grant("actor", ownerActorId, listOf("channel.read", "file.read", "file.write", "channel.manage", "channel.grants.manage")),
            grant("actor", agentActorId, listOf("channel.read", "file.read", "file.write")),
        )
        assertEquals(ownerActorId to agentActorId, ChiefOfStaffAcl.verifyCohortChannelGrants(valid, members, ownerNpub, agentNpub))

        // Group grant must fail
        val withGroup = valid + grant("group", workspaceGroupId, listOf("channel.read", "file.read"))
        assertThrowsIllegal { ChiefOfStaffAcl.verifyCohortChannelGrants(withGroup, members, ownerNpub, agentNpub) }

        // Third actor must fail
        val withThird = valid + grant("actor", memberActorId, listOf("channel.read", "file.read", "file.write"))
        assertThrowsIllegal { ChiefOfStaffAcl.verifyCohortChannelGrants(withThird, members, ownerNpub, agentNpub) }
    }

    @Test fun ordinaryReadOnlyMemberReadsAssignmentWithoutAdminInventoryCalls() = runBlocking {
        val assignment = ChiefOfStaffAssignment(
            workspaceId = wsA,
            workspaceOwnerNpub = ownerNpub,
            appNpub = appNpub,
            assignmentRevision = 1,
            assignedActorId = agentActorId,
            assignedNpub = agentNpub,
            assignedDisplayName = "Rick",
            previousAssignedNpub = null,
            updatedByActorId = ownerActorId,
            updatedByNpub = ownerNpub,
            updatedAt = "2026-11-28T00:00:00Z",
        )
        val bytes = ChiefOfStaffEnvelope.encode(assignment.toJson())
        val sha = Sha256Hex.of(bytes)
        val requestedPaths = mutableListOf<String>()

        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                requestedPaths += path
                // Ordinary member has NO access to admin /groups or /grants endpoints (HTTP 403)
                if (path.endsWith("/groups") || path.endsWith("/grants")) {
                    return HttpResponse(403, """{"code":"permission_denied"}""".toByteArray())
                }
                return when {
                    path.endsWith("/members") -> jsonResp(buildJsonObject {
                        put("members", buildJsonArray {
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", ownerActorId); put("npub", ownerNpub); put("kind", "human"); put("display_name", "Owner") })
                                put("membership", buildJsonObject { put("role", "owner") })
                            })
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", agentActorId); put("npub", agentNpub); put("kind", "agent"); put("display_name", "Agent") })
                                put("membership", buildJsonObject { put("role", "member") })
                            })
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", memberActorId); put("npub", memberNpub); put("kind", "human"); put("display_name", "Member") })
                                put("membership", buildJsonObject { put("role", "member") })
                            })
                        })
                    })
                    path.endsWith("/scopes") -> jsonResp(buildJsonObject {
                        put("scopes", buildJsonArray {
                            add(buildJsonObject {
                                put("id", scopeId); put("workspace_id", wsA); put("name", "Home"); put("kind", "scope")
                            })
                        })
                    })
                    path.endsWith("/scopes/$scopeId/channels") -> jsonResp(buildJsonObject {
                        put("channels", buildJsonArray {
                            add(buildJsonObject {
                                put("id", assignChannelId); put("scope_id", scopeId); put("workspace_id", wsA)
                                put("name", "Chief of Staff"); put("kind", "channel")
                                put("created_by_actor_id", ownerActorId)
                                put("created_by_actor_npub", ownerNpub)
                                put("metadata", ChiefOfStaffContract.assignmentChannelMetadata(wsA, appNpub))
                            })
                        })
                    })
                    path.endsWith("/channels/$assignChannelId/files") -> jsonResp(buildJsonObject {
                        put("files", buildJsonArray {
                            add(fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, ownerActorId, ownerNpub))
                        })
                    })
                    path.endsWith("/files/$assignFileId") -> jsonResp(buildJsonObject {
                        put("file", fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, ownerActorId, ownerNpub))
                    })
                    path.endsWith("/files/$assignFileId/versions/$ver1/object") -> HttpResponse(200, bytes)
                    else -> HttpResponse(404, "{}".toByteArray())
                }
            }
        }

        val api = TowerAPI(TowerClient(TowerOrigin("https://tower.invalid"), transport, testIdentity(3)))
        val result = api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
        assertTrue(result is AssignmentLoadResult.Ready)
        assertEquals(agentNpub, (result as AssignmentLoadResult.Ready).assignment.assignedNpub)
        assertFalse("Ordinary member read must not call /groups", requestedPaths.any { it.endsWith("/groups") })
        assertFalse("Ordinary member read must not call /grants", requestedPaths.any { it.endsWith("/grants") })

        // Canonical substitution must fail closed
        try {
            api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false, expectedCanonicalChannelId = cohortChannelId1)
            fail("Canonical channel substitution must fail closed")
        } catch (e: ChiefOfStaffError.Integrity) {
            assertTrue(e.message!!.contains("identity changed"))
        }

        // Ordinary member must receive Restricted for private cohort without making any network calls
        requestedPaths.clear()
        val cohort = api.readChiefOfStaffCohort(wsA, ownerNpub, appNpub, viewerNpub = memberNpub, agentNpub = agentNpub, assignmentEpoch = 1)
        assertEquals(CohortLoadResult.Restricted, cohort)
        assertTrue(requestedPaths.isEmpty())
    }

    @Test fun forgedNonManagerAssignmentChannelOrFileFailsClosedForOrdinaryMember() = runBlocking {
        // Non-manager member creates a channel & file with matching system_key and self-asserted manager JSON
        val forgedAssignment = ChiefOfStaffAssignment(
            workspaceId = wsA,
            workspaceOwnerNpub = ownerNpub,
            appNpub = appNpub,
            assignmentRevision = 1,
            assignedActorId = agentActorId,
            assignedNpub = agentNpub,
            assignedDisplayName = "Spoofed",
            previousAssignedNpub = null,
            updatedByActorId = ownerActorId, // self-asserted owner in JSON!
            updatedByNpub = ownerNpub,
            updatedAt = "2026-11-28T00:00:00Z",
        )
        val bytes = ChiefOfStaffEnvelope.encode(forgedAssignment.toJson())
        val sha = Sha256Hex.of(bytes)

        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                return when {
                    path.endsWith("/members") -> jsonResp(buildJsonObject {
                        put("members", buildJsonArray {
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", ownerActorId); put("npub", ownerNpub); put("kind", "human") })
                                put("membership", buildJsonObject { put("role", "owner") })
                            })
                            add(buildJsonObject {
                                put("actor", buildJsonObject { put("actor_id", memberActorId); put("npub", memberNpub); put("kind", "human") })
                                put("membership", buildJsonObject { put("role", "member") })
                            })
                        })
                    })
                    path.endsWith("/scopes") -> jsonResp(buildJsonObject {
                        put("scopes", buildJsonArray { add(buildJsonObject { put("id", scopeId); put("workspace_id", wsA); put("name", "Home"); put("kind", "scope") }) })
                    })
                    path.endsWith("/scopes/$scopeId/channels") -> jsonResp(buildJsonObject {
                        put("channels", buildJsonArray {
                            add(buildJsonObject {
                                put("id", assignChannelId); put("scope_id", scopeId); put("workspace_id", wsA)
                                put("name", "Chief of Staff"); put("kind", "channel")
                                // Actual NStack creator is a non-manager member!
                                put("created_by_actor_id", memberActorId)
                                put("created_by_actor_npub", memberNpub)
                                put("metadata", ChiefOfStaffContract.assignmentChannelMetadata(wsA, appNpub))
                            })
                        })
                    })
                    path.endsWith("/channels/$assignChannelId/files") -> jsonResp(buildJsonObject {
                        put("files", buildJsonArray {
                            add(fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, memberActorId, memberNpub))
                        })
                    })
                    path.endsWith("/files/$assignFileId") -> jsonResp(buildJsonObject {
                        put("file", fileJson(assignFileId, assignChannelId, ChiefOfStaffContract.MIME_ASSIGNMENT, ChiefOfStaffContract.assignmentFileMetadata(wsA, appNpub), ver1, bytes.size, sha, memberActorId, memberNpub))
                    })
                    path.endsWith("/files/$assignFileId/versions/$ver1/object") -> HttpResponse(200, bytes)
                    else -> HttpResponse(404, "{}".toByteArray())
                }
            }
        }

        val api = TowerAPI(TowerClient(TowerOrigin("https://tower.invalid"), transport, testIdentity(3)))
        try {
            api.readChiefOfStaffAssignment(wsA, ownerNpub, appNpub, verifyAcl = false)
            fail("Forged non-manager assignment candidate must fail closed")
        } catch (e: ChiefOfStaffError.Integrity) {
            assertTrue(e.message!!.contains("workspace manager"))
        }
    }

    @Test fun cohortEpochIsolationAndCorrectionCasRetry() = runBlocking {
        val jobDoc = buildJsonObject {
            put("schema", ChiefOfStaffContract.SCHEMA_JOB_STATUS); put("schema_version", 1)
            put("workspace_id", wsA); put("workspace_owner_npub", ownerNpub); put("app_npub", appNpub)
            put("cohort_id", ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2))
            put("assignment_epoch", 2); put("bound_agent_npub", agentNpub); put("assignment_revision_seen", 2)
            put("schedule_state", "healthy"); put("topic_count", 1); put("evidence_count", 1)
            put("updated_at", "2026-11-28T01:00:00Z")
        }
        val ontoDoc = buildJsonObject {
            put("schema", ChiefOfStaffContract.SCHEMA_ONTOLOGY); put("schema_version", 1)
            put("workspace_id", wsA); put("workspace_owner_npub", ownerNpub); put("app_npub", appNpub)
            put("cohort_id", ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2))
            put("assignment_epoch", 2); put("agent_npub", agentNpub)
            put("snapshot_revision", 1); put("assignment_revision_seen", 2); put("generated_at", "2026-11-28T01:00:00Z")
            put("coverage", buildJsonObject {})
            put("topics", buildJsonArray {
                add(buildJsonObject {
                    put("id", "topic_launch_1"); put("name", "Launch"); put("kind", "project")
                    put("aliases", buildJsonArray {})
                })
            })
            put("evidence", buildJsonArray {
                add(buildJsonObject {
                    put("id", "ev_1"); put("topic_id", "topic_launch_1"); put("source_type", "task")
                    put("record_id", "aaaaaaaa-1111-4111-8111-111111111111"); put("status", "active")
                    put("explanation", "Primary launch tracker")
                })
            })
        }
        var corrBytes = ChiefOfStaffEnvelope.encode(
            ChiefOfStaffCorrections(wsA, ownerNpub, appNpub, ChiefOfStaffContract.cohortId(ownerNpub, agentNpub, 2), 2, emptyList(), "2026-11-28T01:00:00Z").toJson(),
        )
        var corrVer = ver1
        val jobBytes = ChiefOfStaffEnvelope.encode(jobDoc)
        val ontoBytes = ChiefOfStaffEnvelope.encode(ontoDoc)
        var uploadedBytes: ByteArray? = null
        var uploadCompleted = false
        var versionPosts = 0

        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val path = URI(request.url).path
                return when {
                    path.endsWith("/scopes") -> jsonResp(buildJsonObject {
                        put("scopes", buildJsonArray { add(buildJsonObject { put("id", scopeId); put("workspace_id", wsA); put("name", "Home"); put("kind", "scope") }) })
                    })
                    path.endsWith("/scopes/$scopeId/channels") -> jsonResp(buildJsonObject {
                        put("channels", buildJsonArray {
                            // Epoch 1 channel (must NOT match epoch 2)
                            add(buildJsonObject {
                                put("id", cohortChannelId1); put("scope_id", scopeId); put("workspace_id", wsA)
                                put("name", "Chief of Staff Index (Epoch 1)"); put("kind", "channel")
                                put("metadata", ChiefOfStaffContract.cohortChannelMetadata(wsA, appNpub, ownerNpub, agentNpub, 1))
                            })
                            // Epoch 2 channel
                            add(buildJsonObject {
                                put("id", cohortChannelId2); put("scope_id", scopeId); put("workspace_id", wsA)
                                put("name", "Chief of Staff Index (Epoch 2)"); put("kind", "channel")
                                put("metadata", ChiefOfStaffContract.cohortChannelMetadata(wsA, appNpub, ownerNpub, agentNpub, 2))
                            })
                        })
                    })
                    path.endsWith("/channels/$cohortChannelId2/grants") -> jsonResp(buildJsonObject {
                        put("complete", true)
                        put("grants", buildJsonArray {
                            add(grant("actor", ownerActorId, listOf("channel.read", "file.read", "file.write", "channel.manage", "channel.grants.manage")))
                            add(grant("actor", agentActorId, listOf("channel.read", "file.read", "file.write")))
                        })
                    })
                    path.endsWith("/members") -> jsonResp(buildJsonObject {
                        put("members", buildJsonArray {
                            add(buildJsonObject { put("actor", buildJsonObject { put("actor_id", ownerActorId); put("npub", ownerNpub); put("kind", "human"); put("display_name", "Pete") }) })
                            add(buildJsonObject { put("actor", buildJsonObject { put("actor_id", agentActorId); put("npub", agentNpub); put("kind", "agent"); put("display_name", "Rick") }) })
                        })
                    })
                    path.endsWith("/channels/$cohortChannelId2/files") -> jsonResp(buildJsonObject {
                        put("files", buildJsonArray {
                            add(fileJson(jobFileId, cohortChannelId2, ChiefOfStaffContract.MIME_JOB_STATUS, ChiefOfStaffContract.cohortFileMetadata("job_status", wsA, appNpub, ownerNpub, agentNpub, 2), ver1, jobBytes.size, Sha256Hex.of(jobBytes)))
                            add(fileJson(ontoFileId, cohortChannelId2, ChiefOfStaffContract.MIME_ONTOLOGY, ChiefOfStaffContract.cohortFileMetadata("ontology", wsA, appNpub, ownerNpub, agentNpub, 2), ver1, ontoBytes.size, Sha256Hex.of(ontoBytes)))
                            add(fileJson(corrFileId, cohortChannelId2, ChiefOfStaffContract.MIME_CORRECTIONS, ChiefOfStaffContract.cohortFileMetadata("corrections", wsA, appNpub, ownerNpub, agentNpub, 2), corrVer, corrBytes.size, Sha256Hex.of(corrBytes)))
                        })
                    })
                    path.endsWith("/files/$jobFileId") -> jsonResp(buildJsonObject { put("file", fileJson(jobFileId, cohortChannelId2, ChiefOfStaffContract.MIME_JOB_STATUS, ChiefOfStaffContract.cohortFileMetadata("job_status", wsA, appNpub, ownerNpub, agentNpub, 2), ver1, jobBytes.size, Sha256Hex.of(jobBytes))) })
                    path.endsWith("/files/$ontoFileId") -> jsonResp(buildJsonObject { put("file", fileJson(ontoFileId, cohortChannelId2, ChiefOfStaffContract.MIME_ONTOLOGY, ChiefOfStaffContract.cohortFileMetadata("ontology", wsA, appNpub, ownerNpub, agentNpub, 2), ver1, ontoBytes.size, Sha256Hex.of(ontoBytes))) })
                    path.endsWith("/files/$corrFileId") -> jsonResp(buildJsonObject { put("file", fileJson(corrFileId, cohortChannelId2, ChiefOfStaffContract.MIME_CORRECTIONS, ChiefOfStaffContract.cohortFileMetadata("corrections", wsA, appNpub, ownerNpub, agentNpub, 2), corrVer, corrBytes.size, Sha256Hex.of(corrBytes))) })
                    path.endsWith("/files/$jobFileId/versions/$ver1/object") -> HttpResponse(200, jobBytes)
                    path.endsWith("/files/$ontoFileId/versions/$ver1/object") -> HttpResponse(200, ontoBytes)
                    path.endsWith("/files/$corrFileId/versions/$corrVer/object") -> HttpResponse(200, corrBytes)
                    path.endsWith("/storage/prepare") -> {
                        uploadCompleted = false
                        jsonResp(buildJsonObject {
                            put("object_id", obj1); put("upload_url", "https://upload.invalid/obj1")
                        })
                    }
                    request.url == "https://upload.invalid/obj1" && request.method == "PUT" -> {
                        uploadedBytes = request.body
                        HttpResponse(200, ByteArray(0))
                    }
                    path.endsWith("/storage/$obj1/complete") -> {
                        uploadCompleted = true
                        jsonResp(buildJsonObject { put("completed_at", "2026-11-28T01:01:00Z") })
                    }
                    path.endsWith("/storage/$obj1") -> jsonResp(buildJsonObject {
                        val b = uploadedBytes ?: ByteArray(0)
                        if (uploadCompleted) put("completed_at", "2026-11-28T01:01:00Z")
                        put("sha256_hex", Sha256Hex.of(b))
                        put("size_bytes", b.size)
                        put("is_public", false)
                    })
                    path.endsWith("/files/$corrFileId/versions") && request.method == "POST" -> {
                        versionPosts++
                        if (versionPosts == 1) {
                            // First attempt hits 409 stale_base_version CAS conflict
                            HttpResponse(409, """{"code":"stale_base_version"}""".toByteArray())
                        } else {
                            corrBytes = uploadedBytes!!
                            corrVer = ver2
                            HttpResponse(201, buildJsonObject {
                                put("file", fileJson(corrFileId, cohortChannelId2, ChiefOfStaffContract.MIME_CORRECTIONS, ChiefOfStaffContract.cohortFileMetadata("corrections", wsA, appNpub, ownerNpub, agentNpub, 2), ver2, corrBytes.size, Sha256Hex.of(corrBytes)))
                                put("version", buildJsonObject { put("id", ver2) })
                            }.toString().toByteArray())
                        }
                    }
                    else -> HttpResponse(404, "{}".toByteArray())
                }
            }
        }

        val api = TowerAPI(TowerClient(TowerOrigin("https://tower.invalid"), transport, testIdentity(1)))
        // Epoch 3 does not exist -> PendingSetup
        val pending = api.readChiefOfStaffCohort(wsA, ownerNpub, appNpub, ownerNpub, agentNpub, assignmentEpoch = 3)
        assertEquals(CohortLoadResult.PendingSetup(3), pending)

        // Append correction hits 409 once, retries, and succeeds
        val saved = api.appendChiefOfStaffCorrection(
            workspaceId = wsA,
            ownerNpub = ownerNpub,
            appNpub = appNpub,
            viewerNpub = ownerNpub,
            agentNpub = agentNpub,
            assignmentEpoch = 2,
            correction = ChiefOfStaffCorrection(
                kind = "rename",
                createdByNpub = ownerNpub,
                createdAt = "2026-11-28T01:05:00Z",
                topicId = "topic_launch_1",
                name = "Android Launch",
            ),
        )
        assertEquals(2, versionPosts)
        assertEquals(1, saved.corrections.size)
        assertEquals("Android Launch", saved.corrections.single().name)
    }

    @Test fun applyCorrectionsReplaysRenameMergeRejectPinAndArchive() {
        val topics = listOf(
            ChiefOfStaffTopic("t1", "Alpha", "project", listOf("A"), "First"),
            ChiefOfStaffTopic("t2", "Beta", "project", listOf("B"), "Second"),
        )
        val ev1Id = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        val ev2Id = "22222222-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
        val evidence = listOf(
            ChiefOfStaffEvidence("e1", "t1", "task", ev1Id, explanation = "Good link", score = 0.6),
            ChiefOfStaffEvidence("e2", "t2", "doc", ev2Id, explanation = "Wrong link", score = 0.8),
        )
        val corrections = listOf(
            ChiefOfStaffCorrection("c1", "rename", ownerNpub, "2026-11-28T01:00:00Z", topicId = "t1", name = "Alpha Release"),
            ChiefOfStaffCorrection("c2", "merge_topics", ownerNpub, "2026-11-28T01:01:00Z", sourceTopicId = "t2", targetTopicId = "t1"),
            ChiefOfStaffCorrection("c3", "reject_evidence", ownerNpub, "2026-11-28T01:02:00Z", topicId = "t2", sourceType = "doc", recordId = ev2Id),
            ChiefOfStaffCorrection("c4", "pin_evidence", ownerNpub, "2026-11-28T01:03:00Z", topicId = "t1", sourceType = "task", recordId = ev1Id),
        )
        val applied = ChiefOfStaffOntologyLogic.applyCorrections(topics, evidence, corrections)
        assertEquals(1, applied.topics.size)
        val merged = applied.topics.single()
        assertEquals("Alpha Release", merged.name)
        assertTrue(merged.aliases.containsAll(listOf("Alpha", "Beta", "A", "B")))
        assertEquals(1, applied.evidence.size)
        assertEquals(ev1Id, applied.evidence.single().recordId)
        assertTrue(applied.evidence.single().pinned)
    }

    @Test fun findViewerDirectMessageNeverOpensAnotherMembersOrSelfDm() {
        val dmScope = ScopeRecord(wsA, dmScopeId, "DMs", kind = "dm", updatedAt = "2026-11-28T00:00:00Z")
        val otherMembersDm = ChannelRecord(
            wsA, "10000000-0000-4000-8000-000000000001", dmScopeId, "dm", kind = "dm", updatedAt = "",
            raw = jsonObjectOf("participant_npubs" to listOf(ownerNpub, agentNpub)),
        )
        val selfDm = ChannelRecord(
            wsA, "10000000-0000-4000-8000-000000000002", dmScopeId, "dm", kind = "dm", updatedAt = "",
            raw = jsonObjectOf("participant_npubs" to listOf(memberNpub)),
        )
        val groupDm = ChannelRecord(
            wsA, "10000000-0000-4000-8000-000000000003", dmScopeId, "dm", kind = "dm", updatedAt = "",
            raw = jsonObjectOf("participant_npubs" to listOf(memberNpub, ownerNpub, agentNpub)),
        )
        val viewerOwnDm = ChannelRecord(
            wsA, "10000000-0000-4000-8000-000000000004", dmScopeId, "dm", kind = "dm", updatedAt = "",
            raw = jsonObjectOf("participant_npubs" to listOf(memberNpub, agentNpub)),
        )

        // Without viewerOwnDm, ordinary member must NOT get Pete's DM!
        assertNull(
            ChiefOfStaffOntologyLogic.findViewerDirectMessage(
                listOf(dmScope),
                listOf(otherMembersDm, selfDm, groupDm),
                viewerNpub = memberNpub,
                agentNpub = agentNpub,
            ),
        )

        // With viewerOwnDm present, member gets strictly their own DM
        val matched = ChiefOfStaffOntologyLogic.findViewerDirectMessage(
            listOf(dmScope),
            listOf(otherMembersDm, selfDm, groupDm, viewerOwnDm),
            viewerNpub = memberNpub,
            agentNpub = agentNpub,
        )
        assertEquals(viewerOwnDm.id, matched?.id)
    }

    @Test fun localAssignmentStoreIsolatesWorkspaces() = runBlocking {
        val dir = Files.createTempDirectory("cos-store").toFile()
        val store = LocalStore(dir)
        try {
            val a = ChiefOfStaffAssignment(wsA, ownerNpub, appNpub, 1, agentActorId, agentNpub, "Agent", null, ownerActorId, ownerNpub, "2026-11-28T00:00:00Z")
            ChiefOfStaffStore.saveAssignment(store, "conn1", wsA, appNpub, a, assignChannelId, assignFileId)
            val cached = ChiefOfStaffStore.loadCachedAssignment(store, "conn1", wsA, ownerNpub, appNpub)
            assertEquals(a, cached?.assignment)
            assertEquals(assignChannelId, cached?.canonicalChannelId)
            assertEquals(assignFileId, cached?.canonicalFileId)
            assertNull(ChiefOfStaffStore.loadAssignment(store, "conn1", wsB, ownerNpub, appNpub))
            assertNull(ChiefOfStaffStore.loadAssignment(store, "conn2", wsA, ownerNpub, appNpub))
            // Access loss clears cached assignment cleanly
            ChiefOfStaffStore.clearAssignment(store, "conn1", wsA, appNpub)
            assertNull(ChiefOfStaffStore.loadAssignment(store, "conn1", wsA, ownerNpub, appNpub))

            // Reconcile config is also workspace-isolated
            val cfg = ChiefOfStaffReconcileConfig("https://autopilot.invalid", "chief-of-staff-reconcile.v1", "sub-1")
            ChiefOfStaffReconcileStore.save(store, "conn1", wsA, appNpub, cfg)
            assertEquals(cfg, ChiefOfStaffReconcileStore.load(store, "conn1", wsA, appNpub))
            assertNull(ChiefOfStaffReconcileStore.load(store, "conn1", wsB, appNpub))
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    @Test fun pipelineClientInvokesPinnedDefinitionWithExactHostNoRedirectsAndHonestErrors() = runBlocking {
        val identity = testIdentity(1)
        val signerNpub = (identity.state() as studio.otherstuff.ambulando.core.crypto.IdentityState.Unlocked).npub
        val origin = ConfirmedAutopilotOrigin.of("https://autopilot.invalid")
        val boundary = WorkspaceBoundary("https://tower.invalid", signerNpub, appNpub, wsA)

        // Verify runtime_binding canonical v1 round-trip (required UUID subscription_id + optional/unknown field preservation)
        val subUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val binding = ChiefOfStaffRuntimeBinding(
            autopilotOrigin = "https://autopilot.invalid",
            reconcilePipelineId = "chief-of-staff-reconcile.v1",
            ownerNpub = ownerNpub,
            workspaceId = wsA,
            subscriptionId = subUuid,
            agentNpub = agentNpub,
            ontologyPipelineId = "chief-of-staff-ontology.v1",
            cronExpression = "0 * * * *",
            timezone = "Australia/Perth",
            extra = mapOf("future_hint" to JsonPrimitive("preserved")),
        )
        val withBinding = ChiefOfStaffAssignment(
            workspaceId = wsA, workspaceOwnerNpub = ownerNpub, appNpub = appNpub,
            assignmentRevision = 3, assignedActorId = agentActorId, assignedNpub = agentNpub,
            assignedDisplayName = "Agent", previousAssignedNpub = null,
            updatedByActorId = ownerActorId, updatedByNpub = ownerNpub,
            updatedAt = "2026-11-28T00:00:00Z", runtimeBinding = binding,
        )
        val parsedBinding = ChiefOfStaffAssignment.parse(withBinding.toJson(), ExpectedWorkspaceContext(wsA, ownerNpub, appNpub)).runtimeBinding
        assertEquals(binding, parsedBinding)
        assertEquals(JsonPrimitive("preserved"), parsedBinding?.extra?.get("future_hint"))

        // Non-UUID subscription_id in runtime_binding must be rejected
        assertThrowsIllegal {
            ChiefOfStaffRuntimeBinding.parse(
                buildJsonObject {
                    put("autopilot_origin", "https://autopilot.invalid")
                    put("reconcile_pipeline_id", "chief-of-staff-reconcile.v1")
                    put("owner_npub", ownerNpub)
                    put("workspace_id", wsA)
                    put("subscription_id", "not-a-uuid")
                },
                wsA, ownerNpub, agentNpub,
            )
        }

        var nextStatus = 200
        var nextBody = """{"run":{"id":"run-123","status":"completed"},"replayed":false}"""
        var lastRequest: HttpRequest? = null
        val inner = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                lastRequest = request
                return HttpResponse(nextStatus, nextBody.toByteArray())
            }
        }
        val guarded = BoundaryGuardTransport(inner, boundary)
        val client = ChiefOfStaffPipelineClient(identity, guarded)
        val req = ChiefOfStaffReconcileRequest(
            workspaceId = wsA,
            ownerNpub = ownerNpub,
            appNpub = appNpub,
            assignedAgentNpub = agentNpub,
            assignmentRevision = 3,
            assignmentEpoch = 3,
            towerUrl = "https://tower.invalid",
            subscriptionId = "sub-1",
        )

        // 1. 200 Completed with NIP-98 Authorization and NO X-App-Npub header
        val ok = client.reconcile(origin, signerNpub, "chief-of-staff-reconcile.v1", req)
        assertTrue("Expected Completed, got $ok", ok is ChiefOfStaffReconcileOutcome.Completed)
        assertEquals("run-123", (ok as ChiefOfStaffReconcileOutcome.Completed).runId)
        assertEquals("POST", lastRequest?.method)
        assertEquals("https://autopilot.invalid/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs", lastRequest?.url)
        assertTrue(lastRequest?.header("Authorization")?.startsWith("Nostr ") == true)
        assertNull(lastRequest?.header(TowerClient.APP_NPUB_HEADER))

        // 2. 202 Accepted
        nextStatus = 202
        nextBody = """{"run":{"id":"run-124","status":"running"},"timedOut":true}"""
        val accepted = client.reconcile(origin, signerNpub, "chief-of-staff-reconcile.v1", req)
        assertTrue(accepted is ChiefOfStaffReconcileOutcome.Accepted)

        // 3. 404 DefinitionNotFound
        nextStatus = 404
        nextBody = """{"error":"Pipeline definition not found"}"""
        val missing = client.reconcile(origin, signerNpub, "chief-of-staff-reconcile.v1", req)
        assertTrue(missing is ChiefOfStaffReconcileOutcome.DefinitionNotFound)

        // 4. 302 Redirect refused
        nextStatus = 302
        nextBody = ""
        val redirect = client.reconcile(origin, signerNpub, "chief-of-staff-reconcile.v1", req)
        assertTrue(redirect is ChiefOfStaffReconcileOutcome.Failed && redirect.code == "redirect_refused")

        // 5. BoundaryGuardTransport refuses non-POST, query params, HTTP, or arbitrary /api/pipelines paths
        for (bad in listOf(
            Triple("GET", "https://autopilot.invalid/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs", null),
            Triple("POST", "http://autopilot.invalid/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs", null),
            Triple("POST", "https://autopilot.invalid/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs?foo=1", null),
            Triple("POST", "https://autopilot.invalid/api/pipelines/definitions/chief-of-staff-reconcile.v1/runs", appNpub),
            Triple("POST", "https://autopilot.invalid/api/pipelines/wizard", null),
        )) {
            try {
                guarded.check(bad.second, bad.third, bad.first)
                fail("BoundaryGuardTransport must refuse ${bad.first} ${bad.second}")
            } catch (_: BoundaryViolation) {
                // expected
            }
        }
    }

    private fun grant(type: String, id: String, perms: List<String>): JsonObject = buildJsonObject {
        put("principal_type", type)
        put("principal_id", id)
        put("permissions", buildJsonArray { perms.forEach { add(JsonPrimitive(it)) } })
    }

    private fun fileJson(
        id: String,
        channelId: String,
        mime: String,
        metadata: JsonObject,
        versionId: String,
        size: Int,
        sha: String,
        createdByActorId: String = ownerActorId,
        createdByActorNpub: String = ownerNpub,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("workspace_id", wsA)
        put("channel_id", channelId)
        put("display_name", "Chief of Staff.json")
        put("content_type", mime)
        put("current_version_id", versionId)
        put("created_by_actor_id", createdByActorId)
        put("created_by_actor_npub", createdByActorNpub)
        put("updated_by_actor_id", createdByActorId)
        put("updated_by_actor_npub", createdByActorNpub)
        put("current_version", buildJsonObject {
            put("id", versionId)
            put("created_by_actor_id", createdByActorId)
            put("created_by_actor_npub", createdByActorNpub)
        })
        put("metadata", metadata)
        put("object", buildJsonObject {
            put("storage_object", buildJsonObject {
                put("sha256_hex", sha)
                put("size_bytes", size)
                put("is_public", false)
            })
        })
    }

    private fun jsonResp(obj: JsonObject) = HttpResponse(200, obj.toString().toByteArray())

    private fun assertThrowsIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
