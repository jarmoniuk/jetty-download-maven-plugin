output = new File( basedir, "output.bin" ).text
assert output == "Hello, world!" + System.lineSeparator()
