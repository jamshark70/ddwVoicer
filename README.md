## ddwVoicer
### H. James Harkins
### 2005 - present

`Voicer()` is a voice-stealing note player for SuperCollider, inspired by MIDI synthesizers. In normal SC usage, note control requires access to the specific synth node object. With `Voicer`, you create nodes by frequency, and also release them by frequency. This makes it easy to connect a MIDI keyboard: a incoming note-off needs only to calculate the corresponding frequency and tell the Voicer to release that frequency. The Voicer will find the oldest node playing that pitch, and stop it.

At a later date, I may add examples. For now, I'm adding the README for a specific announcement:

### Branches

- The **master** branch is the current stable release.

- The **topic/rearticulation** branch contains experimental code to support pitch-generation processes in https://github.com/jamshark70/ddwChucklib-livecode. This branch is somewhat well-tested at this point and is not likely to change dramatically, *but* I don't consider it stable as yet.

If you are using ddwChucklib-livecode, I recommend that you check out the rearticulation branch. Otherwise, stick with master.

I believe you can do it like this, assuming you've cloned this repository.

    git fetch origin
    git checkout topic/rearticulation
