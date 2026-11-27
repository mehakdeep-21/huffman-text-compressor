# Treezip: Huffman Text Compressor (Core Java + HTML/CSS)

Web-based text compression app. Java backend (JDK only), HTML/CSS/JS frontend.

## Run
- Windows: `run.bat`    Linux/Mac: `./run.sh`    then open http://localhost:8000
- Tests: `./test.sh`
- Sign up on the first screen, then log in.

## Project requirements -> where they are done
| Requirement (from sir) | Where |
|---|---|
| Enter text for compression | Compress tab (`index.html`) |
| Calculate character frequency | `HuffmanCoder.compress` (frequency array) + code table |
| Generate Huffman codes | `HuffmanCoder.buildTree` / `buildCodes` |
| Display binary encoding | "Encoded bit stream" card |
| Decode compressed text | Decompress tab (`.huf`/Base64) and History "Decode" (bits + code table) |
| Display compression statistics | "Compression results" card |
| Save previous compression records | History tab, `RecordStore` / `FileRecordStore` (`data/records.txt`) |
| Login / sign up | `Auth.java`, `login.html` |

## Data structures and concepts
| Concept | Where |
|---|---|
| Node class | `HuffmanCoder.Node` |
| PriorityQueue | `HuffmanCoder.buildTree` (min-heap) |
| Greedy algorithm | always merge the two smallest-frequency nodes |
| Binary tree | Huffman tree, drawn as SVG |
| Tree traversal | pre/in/post/level-order (`HuffmanCoder.preOrder` ...), recursion for codes |
| HashMap | `codeTable` (byte -> code), `decodeBits` (code -> byte), user/session maps |
| Bit / string handling | bit packing in `compress`, `decodeBits`, `.huf` format |
| OOP | classes, interface `RecordStore`, encapsulation, separation of layers |

## Layers
`index.html` -> `Server` (HTTP) -> `Api` -> `HuffmanCoder` / `Auth` / `RecordStore`

## Project Evaluation 2 (JDBC + MySQL)
Records are saved through the `RecordStore` interface. To use MySQL, write a
`JdbcRecordStore implements RecordStore` (add/list/get/delete with `java.sql`) and change one
line in `Server.java` (`STORE = new JdbcRecordStore(...)`). Nothing else changes.
