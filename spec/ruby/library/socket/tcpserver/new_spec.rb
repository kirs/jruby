require File.expand_path('../../../../spec_helper', __FILE__)
require File.expand_path('../../fixtures/classes', __FILE__)

describe "TCPServer.new" do
  after :each do
    @server.close if @server && !@server.closed?
  end

  it "binds to a host and a port" do
    @server = TCPServer.new('127.0.0.1', SocketSpecs.port)
    addr = @server.addr
    addr[0].should == 'AF_INET'
    addr[1].should be_kind_of(Fixnum)
    # on some platforms (Mac), MRI
    # returns comma at the end.
    addr[2].should =~ /^#{SocketSpecs.hostname}\b/
    addr[3].should == '127.0.0.1'
  end

  it "binds to localhost and a port with either IPv4 or IPv6" do
    @server = TCPServer.new(SocketSpecs.hostname, SocketSpecs.port)
    addr = @server.addr
    if addr[0] == 'AF_INET'
      addr[1].should == SocketSpecs.port
      addr[2].should =~ /^#{SocketSpecs.hostname}\b/
      addr[3].should == '127.0.0.1'
    else
      addr[1].should == SocketSpecs.port
      addr[2].should =~ /^#{SocketSpecs.hostnamev6}\b/
      addr[3].should == '::1'
    end
  end

  it "binds to INADDR_ANY if the hostname is empty" do
    @server = TCPServer.new('', SocketSpecs.port)
    addr = @server.addr
    addr[0].should == 'AF_INET'
    addr[1].should == SocketSpecs.port
    addr[2].should == '0.0.0.0'
    addr[3].should == '0.0.0.0'
  end

  it "binds to INADDR_ANY if the hostname is empty and the port is a string" do
    @server = TCPServer.new('', SocketSpecs.port.to_s)
    addr = @server.addr
    addr[0].should == 'AF_INET'
    addr[1].should == SocketSpecs.port
    addr[2].should == '0.0.0.0'
    addr[3].should == '0.0.0.0'
  end

  it "coerces port to string, then determines port from that number or service name" do
    t = Object.new
    lambda { TCPServer.new(SocketSpecs.hostname, t) }.should raise_error(TypeError)

    def t.to_str; SocketSpecs.port.to_s; end

    @server = TCPServer.new(SocketSpecs.hostname, t)
    addr = @server.addr
    addr[1].should == SocketSpecs.port

    # TODO: This should also accept strings like 'https', but I don't know how to
    # pick such a service port that will be able to reliably bind...
  end

  it "raises Errno::EADDRNOTAVAIL when the adress is unknown" do
    lambda { TCPServer.new("1.2.3.4", 4000) }.should raise_error(Errno::EADDRNOTAVAIL)
  end

  # There is no way to make this fail-proof on all machines, because
  # DNS servers like opendns return A records for ANY host, including
  # traditionally invalidly named ones.
  quarantine! do
    it "raises a SocketError when the host is unknown" do
      lambda {
        TCPServer.new("--notavalidname", 4000)
      }.should raise_error(SocketError)
    end
  end

  it "raises Errno::EADDRINUSE when address is already in use" do
    lambda {
      @server = TCPServer.new('127.0.0.1', SocketSpecs.port)
      @server = TCPServer.new('127.0.0.1', SocketSpecs.port)
    }.should raise_error(Errno::EADDRINUSE)
  end

  platform_is_not :windows, :aix do
    # A known bug in AIX.  getsockopt(2) does not properly set
    # the fifth argument for SO_REUSEADDR.
    it "sets SO_REUSEADDR on the resulting server" do
      @server = TCPServer.new('127.0.0.1', SocketSpecs.port)
      @server.getsockopt(:SOCKET, :REUSEADDR).data.should_not == "\x00\x00\x00\x00"
      @server.getsockopt(:SOCKET, :REUSEADDR).int.should_not == 0
    end
  end
end
