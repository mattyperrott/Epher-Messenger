# Epher Features By Screen

## Overview

Epher is a room-based private chat app for Android. This document translates the current proposal into a user-facing feature map so the product can be designed and built screen by screen.

## 1. Welcome Screen

Purpose:

- explain what Epher is
- show the privacy model in simple language
- route users into create-room or join-room flows

Features:

- short product intro
- "Create Room" primary action
- "Join Room" primary action
- "How Epher Works" secondary action
- permissions primer if needed

## 2. Create Room Screen

Purpose:

- let a user create a new private room

Features:

- create room action
- room name or local label field
- room expiry / retention preset
- optional room size hint
- owner/admin role assigned to creator
- generated room invite package

Outputs:

- room ID
- room invite QR code
- shareable invite link

## 3. Join Room Screen

Purpose:

- let a user enter an existing private room

Features:

- scan QR code
- open invite link
- paste invite token manually
- room preview before join
- join confirmation

Validation:

- room version compatibility check
- malformed invite handling
- revoked or expired invite handling

## 4. Room List Screen

Purpose:

- show all locally known rooms

Features:

- active rooms list
- room names or local labels
- member count snapshot
- unread badge
- connection state badge
- retention / expiry state
- create room shortcut
- join room shortcut

Room states shown:

- active
- reconnecting
- expired
- left

## 5. Active Room Chat Screen

Purpose:

- the main shared group chat experience

Features:

- room title
- room connection status
- message timeline
- send text message
- message composer
- timestamps
- sender display name or nickname
- local ephemeral retention indicator
- leave room action
- open room roster
- open room safety sheet

V1 messaging behavior:

- text only
- no attachments
- no stickers or media features
- no cloud history

## 6. Room Roster Screen

Purpose:

- show who is in the room and their status

Features:

- participant list
- online / recently seen state
- verification or trust state
- role indicator for owner/admin
- join and leave event visibility

Admin actions:

- remove participant
- rotate room access material
- regenerate invite

## 7. Room Safety Sheet

Purpose:

- make the trust and security state visible to the user

Features:

- room identifier
- room version
- participant count
- local device identity fingerprint
- room verification details
- membership event log summary
- encryption status summary
- retention policy summary

This screen should answer:

- am I in the right room
- who is in it
- has membership changed
- what happens to my local data

## 8. Invite Management Screen

Purpose:

- manage how people get into a room

Features:

- current invite QR
- share invite link
- copy invite token
- revoke current invite
- regenerate invite
- optional invite expiry

## 9. Room Settings Screen

Purpose:

- configure room-level behavior

Features:

- local room label
- auto-delete on leave toggle
- room cache expiry setting
- notification preference
- leave room action

Admin-only settings:

- membership policy
- invite rotation
- room closure

## 10. App Settings Screen

Purpose:

- configure device-wide behavior

Features:

- privacy summary
- storage summary
- notification settings
- camera permission for QR scanning
- local identity details
- delete all local room data

## 11. Reconnect / Recovery States

Purpose:

- handle mobile network interruptions gracefully

Features:

- reconnect banner inside room
- peer discovery retry state
- clear failure messaging
- room resume on app foreground
- stale room expiry handling

## 12. Leave / Delete Room Flow

Purpose:

- make the ephemeral behavior visible and trustworthy

Features:

- leave room confirmation
- clear explanation of what local data will be removed
- delete local room cache on leave by default
- final success state after local cleanup

## Cross-Cutting Features

These apply across multiple screens:

- peer-to-peer-first room discovery using Hyperswarm
- no central message storage
- authenticated room membership events
- end-to-end encrypted room messages
- local encrypted cache with expiry
- delete-on-leave behavior by default
- small-room focus for v1

## V1 Exclusions

Not part of the initial screen plan:

- file sharing
- voice or video calls
- push-delivered offline messaging
- large public channels
- multi-device sync
- anonymous broadcast rooms
- Yggdrasil transport mode
