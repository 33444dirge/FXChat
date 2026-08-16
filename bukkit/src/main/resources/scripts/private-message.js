// Private-message sounds for the sender and receiver.
function onPrivateSent(event) {
    event.sound("ENTITY_ITEM_PICKUP", 1, 1, "UI");
}

function onPrivateReceived(event) {
    event.sound("BLOCK_NOTE_BLOCK_PLING", 1, 1.4, "UI");
}
