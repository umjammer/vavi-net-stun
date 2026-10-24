[![Release](https://jitpack.io/v/umjammer/vavi-net-stun.svg)](https://jitpack.io/#umjammer/vavi-net-stun)
[![Java CI](https://github.com/umjammer/vavi-net-stun/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-net-stun/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-net-stun/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-net-stun/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-net-stun

STUN for Java

## Install

## Usage

## References

- [original](https://web.archive.org/web/20040610043750/http://www.ht.sfc.keio.ac.jp/~suno/term/2003s/)
- https://github.com/sisbell/jxta (java p2p ri)

## TODO

  - original
    - Name Resolution - Resolves node name collisions.
    - Topology between parent nodes - Full mesh allows for a smarter implementation.
    - Recovery when a child node is disconnected from its parent node - Simply reconnect to a suitable parent node selected from the parent node list.
  - ~~URLConnection-ize~~
  - Connection Are listeners released when the stream closes?
  - Distributed hashing?
