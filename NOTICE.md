# Notice

Bubble Light's BlueBubbles client is based on [craigeley/chat](https://github.com/craigeley/chat), which is
released under the MIT License. The parts derived from it are the REST client and payload parsing
(`BlueBubblesApi.kt`, `BlueBubblesJson.kt`), the address book matching (`Contacts.kt`), the password storage
(`SecureStore.kt`) and the image decoding (`MediaLoader.kt`). The live connection (`LiveSocket.kt`) follows the
same approach as their Socket.IO service but is a new implementation of the wire protocol. They were reworked
to fit a Room database and the Light SDK, but the approach, and in places the code, is theirs.

```
MIT License

Copyright (c) 2026 Craig Eley

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
