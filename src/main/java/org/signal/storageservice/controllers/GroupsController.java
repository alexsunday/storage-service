/*
 * Copyright 2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storageservice.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.annotation.Timed;
import com.google.common.net.HttpHeaders;
import com.google.protobuf.ByteString;
import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.NotarySignature;
import org.signal.libsignal.zkgroup.ServerSecretParams;
import org.signal.libsignal.zkgroup.groups.UuidCiphertext;
import org.signal.libsignal.zkgroup.groupsend.GroupSendCredentialResponse;
import org.signal.libsignal.zkgroup.profiles.ServerZkProfileOperations;
import org.signal.storageservice.auth.ExternalGroupCredentialGenerator;
import org.signal.storageservice.auth.GroupUser;
import org.signal.storageservice.configuration.GroupConfiguration;
import org.signal.storageservice.groups.GroupAuth;
import org.signal.storageservice.groups.GroupChangeApplicator;
import org.signal.storageservice.groups.GroupValidator;
import org.signal.storageservice.metrics.UserAgentTagUtil;
import org.signal.storageservice.providers.NoUnknownFields;
import org.signal.storageservice.providers.ProtocolBufferMediaType;
import org.signal.storageservice.s3.PolicySigner;
import org.signal.storageservice.s3.PostPolicyGenerator;
import org.signal.storageservice.storage.GroupsManager;
import org.signal.storageservice.storage.protos.groups.AccessControl;
import org.signal.storageservice.storage.protos.groups.AvatarUploadAttributes;
import org.signal.storageservice.storage.protos.groups.ExternalGroupCredential;
import org.signal.storageservice.storage.protos.groups.Group;
import org.signal.storageservice.storage.protos.groups.GroupChange;
import org.signal.storageservice.storage.protos.groups.GroupChange.Actions;
import org.signal.storageservice.storage.protos.groups.GroupChangeResponse;
import org.signal.storageservice.storage.protos.groups.GroupChanges;
import org.signal.storageservice.storage.protos.groups.GroupJoinInfo;
import org.signal.storageservice.storage.protos.groups.GroupResponse;
import org.signal.storageservice.storage.protos.groups.Member;
import org.signal.storageservice.storage.protos.groups.MemberPendingProfileKey;
import org.signal.storageservice.util.CollectionUtil;
import org.signal.storageservice.util.Pair;

@Path("/v2/groups")
public class GroupsController {
  private static final int LOG_VERSION_LIMIT = 64;
  private static final int INVITE_LINKS_CHANGE_EPOCH = 1;
  private static final int DESCRIPTION_CHANGE_EPOCH = 2;
  private static final int ANNOUNCEMENTS_ONLY_CHANGE_EPOCH = 3;
  private static final int BANNED_USERS_CHANGE_EPOCH = 4;
  private static final int JOIN_BY_PNI_EPOCH = 5;

  private static final String LOG_SIZE_BYTES_DISTRIBUTION_SUMMARY_NAME = name(GroupsController.class, "logSizeBytes");

  private final Clock clock;
  private final GroupsManager groupsManager;
  private final GroupConfiguration groupConfiguration;
  private final ServerSecretParams serverSecretParams;
  private final GroupValidator groupValidator;
  private final GroupChangeApplicator groupChangeApplicator;

  private final PolicySigner policySigner;
  private final PostPolicyGenerator policyGenerator;

  private final ExternalGroupCredentialGenerator externalGroupCredentialGenerator;

  public GroupsController(
      Clock clock,
      GroupsManager groupsManager,
      ServerSecretParams serverSecretParams,
      PolicySigner policySigner,
      PostPolicyGenerator policyGenerator,
      GroupConfiguration groupConfiguration,
      ExternalGroupCredentialGenerator externalGroupCredentialGenerator) {
    this.clock = clock;
    this.groupsManager = groupsManager;
    this.groupConfiguration = groupConfiguration;
    this.serverSecretParams = serverSecretParams;
    this.groupValidator = new GroupValidator(new ServerZkProfileOperations(serverSecretParams), groupConfiguration);
    this.groupChangeApplicator = new GroupChangeApplicator(this.groupValidator);
    this.policySigner = policySigner;
    this.policyGenerator = policyGenerator;
    this.externalGroupCredentialGenerator = externalGroupCredentialGenerator;
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  public CompletableFuture<Response> getGroup(@Auth GroupUser user) {
    return groupsManager.getGroup(user.getGroupId()).thenApply(group -> {
      if (group.isEmpty()) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }

      if (GroupAuth.isMember(user, group.get()) || GroupAuth.isMemberPendingProfileKey(user, group.get())) {
        final GroupResponse.Builder responseBuilder = GroupResponse.newBuilder().setGroup(group.get());
        getSerializedGroupSendCredentialIfMember(group.get(), user)
            .ifPresent(responseBuilder::setGroupSendCredentialResponse);
        return Response.ok(responseBuilder.build()).build();
      } else  {
        return Response.status(Response.Status.FORBIDDEN).build();
      }
    });
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Path("/join/{inviteLinkPassword: [^/]*}")
  public CompletableFuture<Response> getGroupJoinInfo(@Auth GroupUser user, @PathParam("inviteLinkPassword") String inviteLinkPasswordString) {
    final byte[] inviteLinkPassword;
    if (StringUtils.isEmpty(inviteLinkPasswordString)) {
      inviteLinkPassword = null;
    } else {
      inviteLinkPassword = Base64.decodeBase64(inviteLinkPasswordString);
    }
    return groupsManager.getGroup(user.getGroupId()).thenApply(group -> {
      if (group.isEmpty()) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }

      final AccessControl.AccessRequired accessRequired = group.get().getAccessControl().getAddFromInviteLink();
      final boolean pendingAdminApproval = GroupAuth.isMemberPendingAdminApproval(user, group.get());
      if (!pendingAdminApproval) {
        if (!MessageDigest.isEqual(inviteLinkPassword, group.get().getInviteLinkPassword().toByteArray())) {
          return Response.status(Response.Status.FORBIDDEN).build();
        }

        if (accessRequired == AccessControl.AccessRequired.UNSATISFIABLE || accessRequired == AccessControl.AccessRequired.UNKNOWN) {
          return Response.status(Response.Status.FORBIDDEN).build();
        }
      }

      if (GroupAuth.isMemberBanned(user, group.get())) {
        return Response.status(Response.Status.FORBIDDEN).header("X-Signal-Forbidden-Reason", "banned").build();
      }

      GroupJoinInfo.Builder groupJoinInfoBuilder = GroupJoinInfo.newBuilder();
      groupJoinInfoBuilder.setPublicKey(group.get().getPublicKey());
      groupJoinInfoBuilder.setTitle(group.get().getTitle());
      groupJoinInfoBuilder.setDescription(group.get().getDescription());
      groupJoinInfoBuilder.setAvatar(group.get().getAvatar());
      groupJoinInfoBuilder.setMemberCount(group.get().getMembersCount());
      groupJoinInfoBuilder.setAddFromInviteLink(accessRequired);
      groupJoinInfoBuilder.setVersion(group.get().getVersion());
      groupJoinInfoBuilder.setPendingAdminApproval(pendingAdminApproval);
      groupJoinInfoBuilder.setPendingAdminApprovalFull(groupValidator.isPendingAdminApprovalFull(group.get()));
      return Response.ok(groupJoinInfoBuilder.build()).build();
    });
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Path("/joined_at_version")
  public CompletableFuture<Response> getJoinedAtVersion(@Auth GroupUser user) {
    return groupsManager.getGroup(user.getGroupId()).thenCompose(group -> {
      if (group.isEmpty()) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.NOT_FOUND).build());
      }

      Optional<Member> member = GroupAuth.getMember(user, group.get());

      if (member.isEmpty()) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.FORBIDDEN).build());
      }

      return CompletableFuture.completedFuture(Response.ok(
            Member.newBuilder()
                  .setJoinedAtVersion(member.get().getJoinedAtVersion())
                  .build())
          .build());
    });
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Path("/logs/{fromVersion}")
  public CompletableFuture<Response> getGroupLogs(
      @Auth GroupUser user,
      @HeaderParam(javax.ws.rs.core.HttpHeaders.USER_AGENT) String userAgent,
      @PathParam("fromVersion") int fromVersion,
      @QueryParam("limit") @DefaultValue("64") int limit,
      @QueryParam("maxSupportedChangeEpoch") Optional<Integer> maxSupportedChangeEpoch,
      @QueryParam("includeFirstState") boolean includeFirstState,
      @QueryParam("includeLastState") boolean includeLastState) {
    return groupsManager.getGroup(user.getGroupId()).thenCompose(group -> {
      if (group.isEmpty()) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.NOT_FOUND).build());
      }

      Optional<Member> member = GroupAuth.getMember(user, group.get());

      if (member.isEmpty()) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.FORBIDDEN).build());
      }

      if (member.get().getJoinedAtVersion() > fromVersion) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.FORBIDDEN).build());
      }

      final int latestGroupVersion = group.get().getVersion();
      if (latestGroupVersion + 1 <= fromVersion) {
        return CompletableFuture.completedFuture(Response.ok(GroupChanges.newBuilder().build()).build());
      }

      final int logVersionLimit = Math.max(1, Math.min(limit, LOG_VERSION_LIMIT)); // 1 ≤ limit ≤ LOG_VERSION_LIMIT
      if (latestGroupVersion + 1 - fromVersion > logVersionLimit) {
        return groupsManager.getChangeRecords(user.getGroupId(), group.get(), maxSupportedChangeEpoch.orElse(null), includeFirstState, includeLastState, fromVersion, fromVersion + logVersionLimit)
                            .thenApply(records -> {
                              final GroupChanges groupChanges = GroupChanges.newBuilder()
                                  .addAllGroupChanges(records)
                                  .build();

                              distributionSummary(LOG_SIZE_BYTES_DISTRIBUTION_SUMMARY_NAME, userAgent)
                                  .record(groupChanges.getSerializedSize());

                              return Response.status(Response.Status.PARTIAL_CONTENT)
                                                            .header(HttpHeaders.CONTENT_RANGE, String.format(Locale.US, "versions %d-%d/%d", fromVersion, fromVersion + logVersionLimit - 1, latestGroupVersion))
                                                            .entity(groupChanges)
                                                            .build();
                            });
      } else {
        return groupsManager.getChangeRecords(user.getGroupId(), group.get(), maxSupportedChangeEpoch.orElse(null), includeFirstState, includeLastState, fromVersion, latestGroupVersion + 1)
                            .thenApply(records -> {
                              final GroupChanges.Builder groupChangesBuilder = GroupChanges.newBuilder()
                                  .addAllGroupChanges(records);

                              getSerializedGroupSendCredentialIfMember(group.get(), user)
                                  .ifPresent(groupChangesBuilder::setGroupSendCredentialResponse);

                              final GroupChanges groupChanges = groupChangesBuilder.build();

                              distributionSummary(LOG_SIZE_BYTES_DISTRIBUTION_SUMMARY_NAME, userAgent)
                                  .record(groupChanges.getSerializedSize());

                              return Response.ok(groupChanges).build();
                            });
      }
    });
  }

  private static DistributionSummary distributionSummary(final String name, final String userAgent) {
    return DistributionSummary.builder(name)
        .publishPercentiles(0.75, 0.95, 0.99, 0.999)
        .distributionStatisticExpiry(Duration.ofMinutes(5))
        .tags(Tags.of(UserAgentTagUtil.getPlatformTag(userAgent)))
        .register(Metrics.globalRegistry);
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Path("/avatar/form")
  public AvatarUploadAttributes getAvatarUploadForm(@Auth GroupUser user) {
    byte[] object = new byte[16];
    new SecureRandom().nextBytes(object);

    String               objectName = "groups/" + Base64.encodeBase64URLSafeString(user.getGroupId().toByteArray()) + "/" + Base64.encodeBase64URLSafeString(object);
    ZonedDateTime        now        = ZonedDateTime.now(ZoneOffset.UTC);
    Pair<String, String> policy     = policyGenerator.createFor(now, objectName, 3 * 1024 * 1024);
    String               signature  = policySigner.getSignature(now, policy.second());

    return AvatarUploadAttributes.newBuilder()
                                 .setKey(objectName)
                                 .setCredential(policy.first())
                                 .setAcl("private")
                                 .setAlgorithm("AWS4-HMAC-SHA256")
                                 .setDate(now.format(PostPolicyGenerator.AWS_DATE_TIME))
                                 .setPolicy(policy.second())
                                 .setSignature(signature)
                                 .build();
  }

  @Timed
  @PUT
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Consumes(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  public CompletableFuture<Response> createGroup(@Auth GroupUser user, @NoUnknownFields Group group) {
    if (group.getVersion() != 0)                                        return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    if (group.getPublicKey() == null || group.getPublicKey().isEmpty()) return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    if (group.getTitle() == null || group.getTitle().isEmpty())         return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());

    if (group.getAccessControl().getAttributes() == AccessControl.AccessRequired.UNKNOWN      ||
        group.getAccessControl().getAttributes() == AccessControl.AccessRequired.UNRECOGNIZED ||
        group.getAccessControl().getMembers() == AccessControl.AccessRequired.UNKNOWN         ||
        group.getAccessControl().getMembers() == AccessControl.AccessRequired.UNRECOGNIZED)
    {
      return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    }

    if (!MessageDigest.isEqual(user.getGroupPublicKey().serialize(), group.getPublicKey().toByteArray())) {
      return CompletableFuture.completedFuture(Response.status(Response.Status.FORBIDDEN).build());
    }

    if (!groupValidator.isValidAvatarUrl(group.getAvatar(), user.getGroupId())) {
      return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    }

    List<Member>                  validatedMembers                  = new LinkedList<>();
    List<MemberPendingProfileKey> validatedMemberPendingProfileKeys = new LinkedList<>();

    for (Member member : group.getMembersList()) {
      validatedMembers.add(groupValidator.validateMember(group, member));
    }

    group = group.toBuilder().clearMembers().addAllMembers(validatedMembers).build();

    Optional<Member> source = GroupAuth.getMember(user, group);

    if (source.isEmpty() || source.get().getRole() != Member.Role.ADMINISTRATOR){
      return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    }

    for (MemberPendingProfileKey memberPendingProfileKey : group.getMembersPendingProfileKeyList()) {
      validatedMemberPendingProfileKeys.add(groupValidator.validateMemberPendingProfileKey(clock, source.get(), group, memberPendingProfileKey));
    }

    group = group.toBuilder().clearMembersPendingProfileKey().addAllMembersPendingProfileKey(validatedMemberPendingProfileKeys).build();

    Stream<ByteString> memberUserIds                   = group.getMembersList().stream().map(Member::getUserId);
    Stream<ByteString> membersPendingProfileKeyUserIds = group.getMembersPendingProfileKeyList().stream().map(memberPendingProfileKey -> memberPendingProfileKey.getMember().getUserId());

    if (CollectionUtil.containsDuplicates(Stream.concat(memberUserIds, membersPendingProfileKeyUserIds).collect(Collectors.toList()))) {
      return CompletableFuture.completedFuture(Response.status(Response.Status.BAD_REQUEST).build());
    }

    if (group.getMembersPendingAdminApprovalCount() > 0) {
      throw new BadRequestException("cannot create a group with already pending members");
    }

    if (group.getMembersBannedCount() > 0) {
      throw new BadRequestException("cannot create a group with already banned members");
    }

    final Group       validatedGroup     = group;
    final GroupChange initialGroupChange = GroupChange.newBuilder()
                                                      .setActions(Actions.newBuilder()
                                                                         .setVersion(0)
                                                                         .setSourceUuid(source.get().getUserId())
                                                                         .build().toByteString())
                                                      .build();

    groupValidator.validateFinalGroupState(validatedGroup);

    return groupsManager.createGroup(user.getGroupId(), validatedGroup)
        .thenCompose(
            created -> {
              if (!created) {
                return CompletableFuture.completedFuture(false);
              } else {
                return groupsManager.appendChangeRecord(user.getGroupId(), 0, initialGroupChange, validatedGroup);
              }
            }).thenApply(
                result -> {
                  if (result) {
                    final GroupResponse.Builder responseBuilder = GroupResponse.newBuilder().setGroup(validatedGroup);
                    getSerializedGroupSendCredentialIfMember(validatedGroup, user)
                        .ifPresent(responseBuilder::setGroupSendCredentialResponse);
                    return Response.ok(responseBuilder.build()).build();
                  } else {
                    return Response.status(Response.Status.CONFLICT).build();
                  }
                });
  }

  @Timed
  @PATCH
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Consumes(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  public CompletableFuture<Response> modifyGroup(
      @Auth GroupUser user,
      @QueryParam("inviteLinkPassword") String inviteLinkPasswordString,
      @NoUnknownFields GroupChange.Actions submittedActions) {
    final byte[] inviteLinkPassword;
    if (StringUtils.isEmpty(inviteLinkPasswordString)) {
      inviteLinkPassword = null;
    } else {
      inviteLinkPassword = Base64.decodeBase64(inviteLinkPasswordString);
    }
    return groupsManager.getGroup(user.getGroupId()).thenCompose(group -> {
      if (group.isEmpty()) {
        throw new BadRequestException("No such group exists");
      }

      if (group.get().getVersion() >= submittedActions.getVersion() || group.get().getVersion() != submittedActions.getVersion() - 1) {
        return CompletableFuture.completedFuture(Response.status(Response.Status.CONFLICT).entity(group.get()).build());
      }

      Actions actions = submittedActions.toBuilder()
                                        .clearAddMembers()
                                        .addAllAddMembers(groupValidator.validateAddMember(user, inviteLinkPassword, group.get(), submittedActions.getAddMembersList()))
                                        .clearAddMembersPendingProfileKey()
                                        .addAllAddMembersPendingProfileKey(groupValidator.validateAddMembersPendingProfileKey(clock, user, group.get(), submittedActions.getAddMembersPendingProfileKeyList()))
                                        .clearAddMembersPendingAdminApproval()
                                        .addAllAddMembersPendingAdminApproval(groupValidator.validateAddMembersPendingAdminApproval(clock, user, inviteLinkPassword, group.get(), submittedActions.getAddMembersPendingAdminApprovalList()))
                                        .clearAddMembersBanned()
                                        .addAllAddMembersBanned(groupValidator.validateAddMembersBanned(clock, user, group.get(), submittedActions.getAddMembersBannedList()))
                                        .clearModifyMemberProfileKeys()
                                        .addAllModifyMemberProfileKeys(groupValidator.validateModifyMemberProfileKeys(user, group.get(), submittedActions.getModifyMemberProfileKeysList()))
                                        .clearPromoteMembersPendingProfileKey()
                                        .addAllPromoteMembersPendingProfileKey(groupValidator.validatePromoteMembersPendingProfileKey(user, group.get(), submittedActions.getPromoteMembersPendingProfileKeyList()))
                                        .clearPromoteMembersPendingPniAciProfileKey()
                                        .addAllPromoteMembersPendingPniAciProfileKey(groupValidator.validatePromoteMemberPendingPniAciProfileKey(user, group.get(), submittedActions.getPromoteMembersPendingPniAciProfileKeyList()))
                                        .build();

      int changeEpoch = 0;

      Group.Builder modifiedGroupBuilder = group.get().toBuilder();

      if (groupChangeApplicator.applyDeleteMembersBanned(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getDeleteMembersBannedList())) {
        changeEpoch = Math.max(changeEpoch, BANNED_USERS_CHANGE_EPOCH);
      }
      if (groupChangeApplicator.applyAddMembersBanned(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getAddMembersBannedList())) {
        changeEpoch = Math.max(changeEpoch, BANNED_USERS_CHANGE_EPOCH);
      }

      groupChangeApplicator.applyAddMembers(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getAddMembersList());
      groupChangeApplicator.applyDeleteMembers(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getDeleteMembersList());
      groupChangeApplicator.applyModifyMemberRoles(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyMemberRolesList());
      groupChangeApplicator.applyModifyMemberProfileKeys(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyMemberProfileKeysList());

      groupChangeApplicator.applyAddMembersPendingProfileKey(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getAddMembersPendingProfileKeyList());
      groupChangeApplicator.applyDeleteMembersPendingProfileKey(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getDeleteMembersPendingProfileKeyList());
      groupChangeApplicator.applyPromoteMembersPendingProfileKey(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getPromoteMembersPendingProfileKeyList());

      if (actions.hasModifyTitle()) groupChangeApplicator.applyModifyTitle(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyTitle());
      if (actions.hasModifyAvatar()) groupChangeApplicator.applyModifyAvatar(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyAvatar());
      if (actions.hasModifyDisappearingMessageTimer()) groupChangeApplicator.applyModifyDisappearingMessageTimer(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyDisappearingMessageTimer());

      if (actions.hasModifyAttributesAccess()) groupChangeApplicator.applyModifyAttributesAccess(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyAttributesAccess());
      if (actions.hasModifyMemberAccess()) groupChangeApplicator.applyModifyMembersAccess(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyMemberAccess());

      if (actions.hasModifyAddFromInviteLinkAccess()) {
        groupChangeApplicator.applyModifyAddFromInviteLinkAccess(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyAddFromInviteLinkAccess());
        changeEpoch = Math.max(changeEpoch, INVITE_LINKS_CHANGE_EPOCH);
      }
      if (actions.getAddMembersPendingAdminApprovalCount() != 0) {
        groupChangeApplicator.applyAddMembersPendingAdminApproval(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getAddMembersPendingAdminApprovalList());
        changeEpoch = Math.max(changeEpoch, INVITE_LINKS_CHANGE_EPOCH);
      }
      if (actions.getDeleteMembersPendingAdminApprovalCount() != 0) {
        groupChangeApplicator.applyDeleteMembersPendingAdminApproval(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getDeleteMembersPendingAdminApprovalList());
        changeEpoch = Math.max(changeEpoch, INVITE_LINKS_CHANGE_EPOCH);
      }
      if (actions.getPromoteMembersPendingAdminApprovalCount() != 0) {
        groupChangeApplicator.applyPromotePendingAdminApproval(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getPromoteMembersPendingAdminApprovalList());
        changeEpoch = Math.max(changeEpoch, INVITE_LINKS_CHANGE_EPOCH);
      }
      if (actions.hasModifyInviteLinkPassword()) {
        groupChangeApplicator.applyModifyInviteLinkPassword(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyInviteLinkPassword());
        changeEpoch = Math.max(changeEpoch, INVITE_LINKS_CHANGE_EPOCH);
      }
      if (actions.hasModifyDescription()) {
        groupChangeApplicator.applyModifyDescription(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyDescription());
        changeEpoch = Math.max(changeEpoch, DESCRIPTION_CHANGE_EPOCH);
      }
      if (actions.hasModifyAnnouncementsOnly()) {
        groupChangeApplicator.applyModifyAnnouncementsOnly(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getModifyAnnouncementsOnly());
        changeEpoch = Math.max(changeEpoch, ANNOUNCEMENTS_ONLY_CHANGE_EPOCH);
      }
      if (actions.getPromoteMembersPendingPniAciProfileKeyCount() != 0) {
        groupChangeApplicator.applyPromoteMembersPendingPniAciProfileKey(user, inviteLinkPassword, group.get(), modifiedGroupBuilder, actions.getPromoteMembersPendingPniAciProfileKeyList());
        changeEpoch = Math.max(changeEpoch, JOIN_BY_PNI_EPOCH);
      }

      Actions.Builder actionsBuilder = actions.toBuilder();
      // this must be the last change applied
      groupChangeApplicator.applyEnsureSomeAdminsExist(actionsBuilder, modifiedGroupBuilder);

      final ByteString sourceUuid = GroupAuth
          .selectChangeSource(user, group.get(), modifiedGroupBuilder::build)
          .orElseThrow(ForbiddenException::new);

      actions = actionsBuilder.setSourceUuid(sourceUuid).build();

      final byte[] serializedActions = actions.toByteArray();
      final int version = actions.getVersion();
      final NotarySignature signature = serverSecretParams.sign(serializedActions);
      final GroupChange signedGroupChange = GroupChange.newBuilder()
          .setActions(ByteString.copyFrom(serializedActions))
          .setServerSignature(ByteString.copyFrom(signature.serialize()))
          .setChangeEpoch(changeEpoch)
          .build();
      final Group updatedGroupState = modifiedGroupBuilder.setVersion(version).build();

      groupValidator.validateFinalGroupState(updatedGroupState);

      return groupsManager.updateGroup(user.getGroupId(), updatedGroupState)
          .thenCompose(result -> {
                if (result.isPresent()) {
                  return CompletableFuture.completedFuture(Response.status(Response.Status.CONFLICT).entity(result.get()).build());
                }

                final GroupChangeResponse.Builder responseBuilder =
                    GroupChangeResponse.newBuilder().setGroupChange(signedGroupChange);
                getSerializedGroupSendCredentialIfMember(updatedGroupState, user)
                    .ifPresent(responseBuilder::setGroupSendCredentialResponse);
                final GroupChangeResponse response = responseBuilder.build();
                return groupsManager.appendChangeRecord(
                    user.getGroupId(), version, signedGroupChange, updatedGroupState)
                    .thenApply(success -> Response.ok(response).build());
              });
        });
  }

  @Timed
  @GET
  @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
  @Path("/token")
  public CompletableFuture<Response> getToken(@Auth GroupUser user) {
    return groupsManager.getGroup(user.getGroupId()).thenApply(group -> {
      if (group.isEmpty()) {
        return Response.status(Response.Status.NOT_FOUND).build();
      }

      Optional<Member> member = GroupAuth.getMember(user, group.get());

      if (member.isPresent()) {
        String token = externalGroupCredentialGenerator.generateFor(
            member.get().getUserId(), user.getGroupId(), GroupAuth.isAllowedToInitiateGroupCall(user, group.get()));
        ExternalGroupCredential credential = ExternalGroupCredential.newBuilder().setToken(token).build();

        return Response.ok(credential).build();
      } else {
        return Response.status(Response.Status.FORBIDDEN).build();
      }
    });
  }

  // Returns a serialized GroupSendCredentialResponse for the user in this group, if the user is a
  // member, and nothing otherwise (which is normal: some endpoints that would normally attach a
  // send credential can legitimately be accessed by group nonmembers (or people may become
  // nonmembers as a result of handling the request, e.g. a modify-group request to leave the
  // group).
  private Optional<ByteString> getSerializedGroupSendCredentialIfMember(Group group, GroupUser user) {
    final Instant expiration = getSendCredentialExpirationTime();

    return GroupAuth.getMember(user, group).map(
        requestingMember ->
            ByteString.copyFrom(
                GroupSendCredentialResponse
                    .issueCredential(
                        group.getMembersList().stream().map(GroupsController::uuidCiphertext).collect(Collectors.toList()),
                        uuidCiphertext(requestingMember),
                        expiration,
                        serverSecretParams,
                        new SecureRandom())
                    .serialize()));
  }

  private static UuidCiphertext uuidCiphertext(Member member) {
    try {
      return new UuidCiphertext(member.getUserId().toByteArray());
    } catch (InvalidInputException e) {
      // we already know this is a valid userid because it came either from our own storage or already-validated auth
      throw new AssertionError(e);
    }
  }

  private Instant getSendCredentialExpirationTime() {
    final Instant now = clock.instant();

    // We must truncate to a day boundary or libsignal will reject the credential to prevent fingerprinting
    final Instant expiration = now.plus(groupConfiguration.groupSendCredentialExpirationTime()).truncatedTo(ChronoUnit.DAYS);

    if (Duration.between(now, expiration).compareTo(groupConfiguration.groupSendCredentialMinimumLifetime()) < 0) {
      // We're close enough to the end of the UTC day that we would issue a uselessly short send
      // credential; extend it by a full day so we're still day-boundary-aligned
      return expiration.plus(Duration.ofDays(1));
    }
    return expiration;
  }

}
