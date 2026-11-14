# MESSAGE_ROUTING_SECURITY_AUDIT

## Scope
This audit reviews the NEXA message flow for one-to-one and local conversation routing, focusing on identity binding, delivery authorization, and strict chat isolation.

## Existing flow identified
- Outgoing messages are created in the local repository layer and assigned a canonical pairwise conversation ID.
- The canonical one-to-one id is derived as `min(nxIdA, nxIdB) + "_" + max(nxIdA, nxIdB)`.
- Local message persistence is filtered by `conversationId`, and the UI subscribes to message streams by conversation.
- Incoming packets pass through the universal transport ingestion pipeline before persistence.
- WebSocket, SMS, and DTN ingress all converge on the same ingestion logic.

## Routing validation currently enforced
- Packet packetId and type are required before processing.
- Local recipients are validated to reject packets intended for another account.
- Typing payloads verify the conversation ID matches the expected pairwise conversation for the sender/recipient pair.
- Repository-level writes now derive the conversation ID from the authenticated local user and sender/recipient identity instead of trusting a caller-supplied chat label.

## Missing validation identified before remediation
- Some local repository implementations were persisting outgoing and incoming messages under a raw peer identifier instead of the canonical pairwise conversation ID.
- Inbound message ingestion did not reject packets whose recipient field did not match the active local account.
- Typing payloads could be processed without checking conversation scope.
- Message persistence could silently move messages into the wrong conversation if a caller passed a mismatched identifier.

## Risk summary
This is a chat-isolation defect: a message from one conversation could be incorrectly routed into another conversation if the conversation identifier was not canonicalized and validated against the authenticated user and peer identity.

## Remediation applied
- Canonical conversation ID derivation is used consistently for direct-message conversations.
- Storage now uses the exact expected conversation for both outbound and inbound messages.
- Local recipient validation rejects packets that are labeled for a different destination account.
- Inbound typing events reject mismatched conversation IDs before they reach the UI thread.

## Security rule
A message is only valid if its sender, recipient, conversation identity, and local authenticated session all agree on the same canonical route before the message is persisted or displayed.
