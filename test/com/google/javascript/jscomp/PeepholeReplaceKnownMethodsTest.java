/*
 * Copyright 2011 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

/**
 * Unit tests for {#link {@link PeepholeReplaceKnownMethods}
 *
 */
public final class PeepholeReplaceKnownMethodsTest extends TypeICompilerTestCase {

  private boolean late = true;
  private boolean useTypes = true;

  public PeepholeReplaceKnownMethodsTest() {
    super(MINIMAL_EXTERNS + LINE_JOINER.join(
        // NOTE: these are defined as variadic to avoid wrong-argument-count warnings in NTI,
        // which enables testing that the pass does not touch calls with wrong argument count.
        "/** @type {function(this: string, ...*): string} */ String.prototype.substring;",
        "/** @type {function(this: string, ...*): string} */ String.prototype.substr;",
        "/** @type {function(this: string, ...*): string} */ String.prototype.slice;",
        "/** @type {function(this: string, ...*): string} */ String.prototype.charAt;",
        "/** @type {function(this: Array, ...*): !Array} */ Array.prototype.slice;"));
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    late = true;
    useTypes = true;
    this.mode = TypeInferenceMode.NEITHER;
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    return new PeepholeOptimizationsPass(
        compiler, getName(), new PeepholeReplaceKnownMethods(late, useTypes));
  }

  public void testStringIndexOf() {
    fold("x = 'abcdef'.indexOf('b')", "x = 1");
    fold("x = 'abcdefbe'.indexOf('b', 2)", "x = 6");
    fold("x = 'abcdef'.indexOf('bcd')", "x = 1");
    fold("x = 'abcdefsdfasdfbcdassd'.indexOf('bcd', 4)", "x = 13");

    fold("x = 'abcdef'.lastIndexOf('b')", "x = 1");
    fold("x = 'abcdefbe'.lastIndexOf('b')", "x = 6");
    fold("x = 'abcdefbe'.lastIndexOf('b', 5)", "x = 1");

    // Both elements must be strings. Don't do anything if either one is not
    // string.
    fold("x = 'abc1def'.indexOf(1)", "x = 3");
    fold("x = 'abcNaNdef'.indexOf(NaN)", "x = 3");
    fold("x = 'abcundefineddef'.indexOf(undefined)", "x = 3");
    fold("x = 'abcnulldef'.indexOf(null)", "x = 3");
    fold("x = 'abctruedef'.indexOf(true)", "x = 3");

    // The following test case fails with JSC_PARSE_ERROR. Hence omitted.
    // foldSame("x = 1.indexOf('bcd');");
    foldSame("x = NaN.indexOf('bcd')");
    foldSame("x = undefined.indexOf('bcd')");
    foldSame("x = null.indexOf('bcd')");
    foldSame("x = true.indexOf('bcd')");
    foldSame("x = false.indexOf('bcd')");

    // Avoid dealing with regex or other types.
    foldSame("x = 'abcdef'.indexOf(/b./)");
    foldSame("x = 'abcdef'.indexOf({a:2})");
    foldSame("x = 'abcdef'.indexOf([1,2])");

    // Template Strings
    foldSame("x = `abcdef`.indexOf('b')");
    foldSame("x = `Hello ${name}`.indexOf('a')");
    foldSame("x = tag `Hello ${name}`.indexOf('a')");
  }

  public void testStringJoinAddSparse() {
    fold("x = [,,'a'].join(',')", "x = ',,a'");
  }

  public void testNoStringJoin() {
    foldSame("x = [].join(',',2)");
    foldSame("x = [].join(f)");
  }

  public void testStringJoinAdd() {
    fold("x = ['a', 'b', 'c'].join('')", "x = \"abc\"");
    fold("x = [].join(',')", "x = \"\"");
    fold("x = ['a'].join(',')", "x = \"a\"");
    fold("x = ['a', 'b', 'c'].join(',')", "x = \"a,b,c\"");
    fold("x = ['a', foo, 'b', 'c'].join(',')",
        "x = [\"a\",foo,\"b,c\"].join()");
    fold("x = [foo, 'a', 'b', 'c'].join(',')",
        "x = [foo,\"a,b,c\"].join()");
    fold("x = ['a', 'b', 'c', foo].join(',')",
        "x = [\"a,b,c\",foo].join()");

    // Works with numbers
    fold("x = ['a=', 5].join('')", "x = \"a=5\"");
    fold("x = ['a', '5'].join(7)", "x = \"a75\"");

    // Works on boolean
    fold("x = ['a=', false].join('')", "x = \"a=false\"");
    fold("x = ['a', '5'].join(true)", "x = \"atrue5\"");
    fold("x = ['a', '5'].join(false)", "x = \"afalse5\"");

    // Only optimize if it's a size win.
    fold("x = ['a', '5', 'c'].join('a very very very long chain')",
         "x = [\"a\",\"5\",\"c\"].join(\"a very very very long chain\")");

    // Template strings
    foldSame("x = [`a`, `b`, `c`].join(``)");
    foldSame("x = [`a`, `b`, `c`].join('')");

    // TODO(user): Its possible to fold this better.
    foldSame("x = ['', foo].join('-')");
    foldSame("x = ['', foo, ''].join()");

    fold("x = ['', '', foo, ''].join(',')",
         "x = [',', foo, ''].join()");
    fold("x = ['', '', foo, '', ''].join(',')",
         "x = [',', foo, ','].join()");

    fold("x = ['', '', foo, '', '', bar].join(',')",
         "x = [',', foo, ',', bar].join()");

    fold("x = [1,2,3].join('abcdef')",
         "x = '1abcdef2abcdef3'");

    fold("x = [1,2].join()", "x = '1,2'");
    fold("x = [null,undefined,''].join(',')", "x = ',,'");
    fold("x = [null,undefined,0].join(',')", "x = ',,0'");
    // This can be folded but we don't currently.
    foldSame("x = [[1,2],[3,4]].join()"); // would like: "x = '1,2,3,4'"
  }

  public void testStringJoinAdd_b1992789() {
    fold("x = ['a'].join('')", "x = \"a\"");
    fold("x = [foo()].join('')", "x = '' + foo()");
    fold("[foo()].join('')", "'' + foo()");
  }

  public void testFoldStringSubstr() {
    fold("x = 'abcde'.substr(0,2)", "x = 'ab'");
    fold("x = 'abcde'.substr(1,2)", "x = 'bc'");
    fold("x = 'abcde'['substr'](1,3)", "x = 'bcd'");
    fold("x = 'abcde'.substr(2)", "x = 'cde'");

    // we should be leaving negative indexes alone for now
    foldSame("x = 'abcde'.substr(-1)");
    foldSame("x = 'abcde'.substr(1, -2)");
    foldSame("x = 'abcde'.substr(1, 2, 3)");
    foldSame("x = 'a'.substr(0, 2)");

    // Template strings
    foldSame("x = `abcdef`.substr(0,2)");
    foldSame("x = `abc ${xyz} def`.substr(0,2)");
  }

  public void testFoldStringSubstring() {
    fold("x = 'abcde'.substring(0,2)", "x = 'ab'");
    fold("x = 'abcde'.substring(1,2)", "x = 'b'");
    fold("x = 'abcde'['substring'](1,3)", "x = 'bc'");
    fold("x = 'abcde'.substring(2)", "x = 'cde'");

    // we should be leaving negative, out-of-bound, and inverted indices alone for now
    foldSame("x = 'abcde'.substring(-1)");
    foldSame("x = 'abcde'.substring(1, -2)");
    foldSame("x = 'abcde'.substring(1, 2, 3)");
    foldSame("x = 'abcde'.substring(2, 0)");
    foldSame("x = 'a'.substring(0, 2)");

    // Template strings
    foldSame("x = `abcdef`.substring(0,2)");
    foldSame("x = `abcdef ${abc}`.substring(0,2)");
  }

  public void testFoldStringSlice() {
    fold("x = 'abcde'.slice(0,2)", "x = 'ab'");
    fold("x = 'abcde'.slice(1,2)", "x = 'b'");
    fold("x = 'abcde'['slice'](1,3)", "x = 'bc'");
    fold("x = 'abcde'.slice(2)", "x = 'cde'");

    // we should be leaving negative, out-of-bound, and inverted indices alone for now
    foldSame("x = 'abcde'.slice(-1)");
    foldSame("x = 'abcde'.slice(1, -2)");
    foldSame("x = 'abcde'.slice(1, 2, 3)");
    foldSame("x = 'abcde'.slice(2, 0)");
    foldSame("x = 'a'.slice(0, 2)");

    // Template strings
    foldSame("x = `abcdef`.slice(0,2)");
    foldSame("x = `abcdef ${abc}`.slice(0,2)");
  }

  public void testFoldStringCharAt() {
    fold("x = 'abcde'.charAt(0)", "x = 'a'");
    fold("x = 'abcde'.charAt(1)", "x = 'b'");
    fold("x = 'abcde'.charAt(2)", "x = 'c'");
    fold("x = 'abcde'.charAt(3)", "x = 'd'");
    fold("x = 'abcde'.charAt(4)", "x = 'e'");
    foldSame("x = 'abcde'.charAt(5)");  // or x = ''
    foldSame("x = 'abcde'.charAt(-1)");  // or x = ''
    foldSame("x = 'abcde'.charAt(y)");
    foldSame("x = 'abcde'.charAt()");  // or x = 'a'
    foldSame("x = 'abcde'.charAt(0, ++z)");  // or (++z, 'a')
    foldSame("x = 'abcde'.charAt(null)");  // or x = 'a'
    foldSame("x = 'abcde'.charAt(true)");  // or x = 'b'
    fold("x = '\\ud834\udd1e'.charAt(0)", "x = '\\ud834'");
    fold("x = '\\ud834\udd1e'.charAt(1)", "x = '\\udd1e'");

    // Template strings
    foldSame("x = `abcdef`.charAt(0)");
    foldSame("x = `abcdef ${abc}`.charAt(0)");
  }

  public void testFoldStringCharCodeAt() {
    fold("x = 'abcde'.charCodeAt(0)", "x = 97");
    fold("x = 'abcde'.charCodeAt(1)", "x = 98");
    fold("x = 'abcde'.charCodeAt(2)", "x = 99");
    fold("x = 'abcde'.charCodeAt(3)", "x = 100");
    fold("x = 'abcde'.charCodeAt(4)", "x = 101");
    foldSame("x = 'abcde'.charCodeAt(5)");  // or x = (0/0)
    foldSame("x = 'abcde'.charCodeAt(-1)");  // or x = (0/0)
    foldSame("x = 'abcde'.charCodeAt(y)");
    foldSame("x = 'abcde'.charCodeAt()");  // or x = 97
    foldSame("x = 'abcde'.charCodeAt(0, ++z)");  // or (++z, 97)
    foldSame("x = 'abcde'.charCodeAt(null)");  // or x = 97
    foldSame("x = 'abcde'.charCodeAt(true)");  // or x = 98
    fold("x = '\\ud834\udd1e'.charCodeAt(0)", "x = 55348");
    fold("x = '\\ud834\udd1e'.charCodeAt(1)", "x = 56606");

    // Template strings
    foldSame("x = `abcdef`.charCodeAt(0)");
    foldSame("x = `abcdef ${abc}`.charCodeAt(0)");
  }

  public void testFoldStringSplit() {
    late = false;
    fold("x = 'abcde'.split('foo')", "x = ['abcde']");
    fold("x = 'abcde'.split()", "x = ['abcde']");
    fold("x = 'abcde'.split(null)", "x = ['abcde']");
    fold("x = 'a b c d e'.split(' ')", "x = ['a','b','c','d','e']");
    fold("x = 'a b c d e'.split(' ', 0)", "x = []");
    fold("x = 'abcde'.split('cd')", "x = ['ab','e']");
    fold("x = 'a b c d e'.split(' ', 1)", "x = ['a']");
    fold("x = 'a b c d e'.split(' ', 3)", "x = ['a','b','c']");
    fold("x = 'a b c d e'.split(null, 1)", "x = ['a b c d e']");
    fold("x = 'aaaaa'.split('a')", "x = ['', '', '', '', '', '']");
    fold("x = 'xyx'.split('x')", "x = ['', 'y', '']");

    // Empty separator
    fold("x = 'abcde'.split('')", "x = ['a','b','c','d','e']");
    fold("x = 'abcde'.split('', 3)", "x = ['a','b','c']");

    // Empty separator AND empty string
    fold("x = ''.split('')", "x = []");

    // Separator equals string
    fold("x = 'aaa'.split('aaa')", "x = ['','']");
    fold("x = ' '.split(' ')", "x = ['','']");

    foldSame("x = 'abcde'.split(/ /)");
    foldSame("x = 'abcde'.split(' ', -1)");

    // Template strings
    foldSame("x = `abcdef`.split()");
    foldSame("x = `abcdef ${abc}`.split()");

    late = true;
    foldSame("x = 'a b c d e'.split(' ')");
  }

  public void testJoinBug() {
    fold("var x = [].join();", "var x = '';");
    fold("var x = [x].join();", "var x = '' + x;");
    foldSame("var x = [x,y].join();");
    foldSame("var x = [x,y,z].join();");

    foldSame(
        LINE_JOINER.join(
            "shape['matrix'] = [",
            "    Number(headingCos2).toFixed(4),",
            "    Number(-headingSin2).toFixed(4),",
            "    Number(headingSin2 * yScale).toFixed(4),",
            "    Number(headingCos2 * yScale).toFixed(4),",
            "    0,",
            "    0",
            "  ].join()"));
  }

  public void testToUpper() {
    fold("'a'.toUpperCase()", "'A'");
    fold("'A'.toUpperCase()", "'A'");
    fold("'aBcDe'.toUpperCase()", "'ABCDE'");

    foldSame("`abc`.toUpperCase()");
    foldSame("`a ${bc}`.toUpperCase()");
  }

  public void testToLower() {
    fold("'A'.toLowerCase()", "'a'");
    fold("'a'.toLowerCase()", "'a'");
    fold("'aBcDe'.toLowerCase()", "'abcde'");

    foldSame("`ABC`.toLowerCase()");
    foldSame("`A ${BC}`.toUpperCase()");
  }

  public void testFoldParseNumbers() {
    enableNormalize();

    // Template Strings
    foldSame("x = parseInt(`123`)");
    foldSame("x = parseInt(` 123`)");
    foldSame("x = parseInt(`12 ${a}`)");
    foldSame("x = parseFloat(`1.23`)");

    setAcceptedLanguage(LanguageMode.ECMASCRIPT5);

    fold("x = parseInt('123')", "x = 123");
    fold("x = parseInt(' 123')", "x = 123");
    fold("x = parseInt('123', 10)", "x = 123");
    fold("x = parseInt('0xA')", "x = 10");
    fold("x = parseInt('0xA', 16)", "x = 10");
    fold("x = parseInt('07', 8)", "x = 7");
    fold("x = parseInt('08')", "x = 8");
    fold("x = parseInt('0')", "x = 0");
    fold("x = parseFloat('0')", "x = 0");
    fold("x = parseFloat('1.23')", "x = 1.23");
    fold("x = parseFloat('1.2300')", "x = 1.23");
    fold("x = parseFloat(' 0.3333')", "x = 0.3333");
    fold("x = parseFloat('0100')", "x = 100");
    fold("x = parseFloat('0100.000')", "x = 100");

    //Mozilla Dev Center test cases
    fold("x = parseInt(' 0xF', 16)", "x = 15");
    fold("x = parseInt(' F', 16)", "x = 15");
    fold("x = parseInt('17', 8)", "x = 15");
    fold("x = parseInt('015', 10)", "x = 15");
    fold("x = parseInt('1111', 2)", "x = 15");
    fold("x = parseInt('12', 13)", "x = 15");
    fold("x = parseInt(15.99, 10)", "x = 15");
    fold("x = parseFloat('3.14')", "x = 3.14");
    fold("x = parseFloat(3.14)", "x = 3.14");

    //Valid calls - unable to fold
    foldSame("x = parseInt('FXX123', 16)");
    foldSame("x = parseInt('15*3', 10)");
    foldSame("x = parseInt('15e2', 10)");
    foldSame("x = parseInt('15px', 10)");
    foldSame("x = parseInt('-0x08')");
    foldSame("x = parseInt('1', -1)");
    foldSame("x = parseFloat('3.14more non-digit characters')");
    foldSame("x = parseFloat('314e-2')");
    foldSame("x = parseFloat('0.0314E+2')");
    foldSame("x = parseFloat('3.333333333333333333333333')");

    //Invalid calls
    foldSame("x = parseInt('0xa', 10)");
    foldSame("x = parseInt('')");

    setAcceptedLanguage(LanguageMode.ECMASCRIPT3);
    foldSame("x = parseInt('08')");
  }

  public void testFoldParseOctalNumbers() {
    setAcceptedLanguage(LanguageMode.ECMASCRIPT5);
    enableNormalize();
    setExpectParseWarningsThisTest();

    fold("x = parseInt(021, 8)", "x = 15");
  }

  public void testReplaceWithCharAt() {
    this.mode = TypeInferenceMode.BOTH;
    foldStringTyped("a.substring(0, 1)", "a.charAt(0)");
    foldStringTyped("a.substring(-4, -3)", "a.charAt(-4)");
    foldSameStringTyped("a.substring(i, j + 1)");
    foldSameStringTyped("a.substring(i, i + 1)");
    foldSameStringTyped("a.substring(1, 2, 3)");
    foldSameStringTyped("a.substring()");
    foldSameStringTyped("a.substring(1)");
    foldSameStringTyped("a.substring(1, 3, 4)");
    foldSameStringTyped("a.substring(-1, 3)");
    foldSameStringTyped("a.substring(2, 1)");
    foldSameStringTyped("a.substring(3, 1)");

    foldStringTyped("a.slice(4, 5)", "a.charAt(4)");
    foldStringTyped("var /** number */ i; a.slice(0, 1)", "var /** number */ i; a.charAt(0)");
    foldSameStringTyped("a.slice(i, j + 1)");
    foldSameStringTyped("a.slice(i, i + 1)");
    foldSameStringTyped("a.slice(1, 2, 3)");
    foldSameStringTyped("a.slice()");
    foldSameStringTyped("a.slice(1)");
    foldSameStringTyped("a.slice(1, 3, 4)");
    foldSameStringTyped("a.slice(-1, 3)");
    foldSameStringTyped("a.slice(2, 1)");
    foldSameStringTyped("a.slice(3, 1)");

    foldStringTyped("a.substr(0, 1)", "a.charAt(0)");
    foldStringTyped("a.substr(2, 1)", "a.charAt(2)");
    foldSameStringTyped("a.substr(-2, 1)");
    foldSameStringTyped("a.substr(bar(), 1)");
    foldSameStringTyped("''.substr(bar(), 1)");
    foldSameStringTyped("a.substr(2, 1, 3)");
    foldSameStringTyped("a.substr(1, 2, 3)");
    foldSameStringTyped("a.substr()");
    foldSameStringTyped("a.substr(1)");
    foldSameStringTyped("a.substr(1, 2)");
    foldSameStringTyped("a.substr(1, 2, 3)");

    this.mode = TypeInferenceMode.OTI_ONLY;
    // TODO(sdh): NTI currently infers that a is a string, allowing the peephole pass
    // to rewrite substring to charAt.  We need to figure out if this is desirable.
    foldSame("function f(/** ? */ a) { a.substring(0, 1); }");
    foldSame("function f(/** ? */ a) { a.substr(0, 1); }");
    foldSame(LINE_JOINER.join(
        "/** @constructor */ function A() {};",
        "A.prototype.substring = function() {};",
        "function f(/** ? */ a) { a.substring(0, 1); }"));
    this.mode = TypeInferenceMode.BOTH;
    foldSame("function f(/** ? */ a) { a.slice(0, 1); }");

    useTypes = false;
    foldSameStringTyped("a.substring(0, 1)");
    foldSameStringTyped("a.substr(0, 1)");
    foldSameStringTyped("''.substring(i, i + 1)");
  }

  private void foldSame(String js) {
    testSame(js);
  }

  private void fold(String js, String expected) {
    test(js, expected);
  }

  private void foldSameStringTyped(String js) {
    foldStringTyped(js, js);
  }

  private void foldStringTyped(String js, String expected) {
    test(
        "function f(/** string */ a) {" + js + "}",
        "function f(/** string */ a) {" + expected + "}");
  }
}
