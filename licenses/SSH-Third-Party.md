# SSH library notices

These libraries are distributed with the application. License texts apply to
their respective components, independently of Keyrook's GPL-3.0-or-later license.

| Component | Version | License |
| --- | --- | --- |
| Apache MINA SSHD common utilities (`org.apache.sshd:sshd-common`) | 2.19.0 | Apache-2.0, with ISC-licensed BCrypt code |
| Bouncy Castle PKIX and utility modules (`bcpkix-jdk18on`, `bcutil-jdk18on`) | 1.86 | MIT |
| SLF4J API (`org.slf4j:slf4j-api`) | 1.7.36 | MIT |
| Commons Logging bridge (`org.slf4j:jcl-over-slf4j`) | 1.7.36 | Apache-2.0 |

The Bouncy Castle notice is in [Bouncy-Castle.md](Bouncy-Castle.md).
The Apache license is in [Apache-2.0.txt](Apache-2.0.txt).
The Commons Logging bridge has its own Apache license declaration; the SLF4J
API's MIT license does not replace it.

## Apache MINA SSHD notice

From `META-INF/NOTICE` of the
[2.19.0 source artifact](https://repo.maven.apache.org/maven2/org/apache/sshd/sshd-common/2.19.0/sshd-common-2.19.0-sources.jar):

```text
Apache MINA SSHD
Copyright 2018-2026 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

## BCrypt notice (ISC)

The OpenSSH bcrypt derivation implementation in `sshd-common` retains this
notice in `org/apache/sshd/common/config/keys/loader/openssh/kdf/BCrypt.java`:

```text
Copyright (c) 2006 Damien Miller <djm@mindrot.org>

Permission to use, copy, modify, and distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## SLF4J API notice (MIT)

From the [SLF4J 1.7.36 license](https://github.com/qos-ch/slf4j/blob/v_1.7.36/LICENSE.txt):

```text
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
