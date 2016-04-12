package com.wavefront.ingester;

import org.junit.Test;
import org.easymock.EasyMock;

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelHandlerContext;
import sunnylabs.report.ReportPoint;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests for OpenTSDBDecoder.
 *
 * @author Clement Pang (clement@wavefront.com).
 */
public class OpenTSDBDecoderTest {

  @Test
  public void testDoubleFormat() throws Exception {
    List<String> customSourceTags = new ArrayList<String>();
    customSourceTags.add("fqdn");
    OpenTSDBDecoder decoder = new OpenTSDBDecoder("localhost", customSourceTags);
    List<ReportPoint> out = new ArrayList<>();
    decoder.decodeReportPoints(null, "put tsdb.vehicle.charge.battery_level 12345.678 93.123e3 host=vehicle_2554", out);
    ReportPoint point = out.get(0);
    assertEquals("dummy", point.getTable());
    assertEquals("tsdb.vehicle.charge.battery_level", point.getMetric());
    assertEquals(93123.0, point.getValue());
    assertEquals(12345678L, point.getTimestamp().longValue());
    assertEquals("vehicle_2554", point.getHost());

    try {
      // need "PUT"
      decoder.decodeReportPoints(null, "tsdb.vehicle.charge.battery_level 12345.678 93.123e3 host=vehicle_2554", out);
      fail();
    } catch (Exception ex) {
    }

    try {
      // need "timestamp"
      decoder.decodeReportPoints(null, "put tsdb.vehicle.charge.battery_level 93.123e3 host=vehicle_2554", out);
      fail();
    } catch (Exception ex) {
    }

    try {
      // need "value"
      decoder.decodeReportPoints(null, "put tsdb.vehicle.charge.battery_level 12345.678 host=vehicle_2554", out);
      fail();
    } catch (Exception ex) {
    }

    out = new ArrayList<>();
    decoder.decodeReportPoints(null, "put tsdb.vehicle.charge.battery_level 12345.678 93.123e3", out);
    point = out.get(0);
    assertEquals("dummy", point.getTable());
    assertEquals("tsdb.vehicle.charge.battery_level", point.getMetric());
    assertEquals(93123.0, point.getValue());
    assertEquals(12345678L, point.getTimestamp().longValue());
    assertEquals("localhost", point.getHost());

    // adaptive timestamp (13-char timestamp is millis).
    out = new ArrayList<>();
    final long now = System.currentTimeMillis();
    decoder.decodeReportPoints(null, "put tsdb.vehicle.charge.battery_level " + now
        + " 93.123e3", out);
    point = out.get(0);
    assertEquals("dummy", point.getTable());
    assertEquals("tsdb.vehicle.charge.battery_level", point.getMetric());
    assertEquals(93123.0, point.getValue());
    assertEquals(now, point.getTimestamp().longValue());
    assertEquals("localhost", point.getHost());

    out = new ArrayList<>();
    decoder.decodeReportPoints(null, "put tail.kernel.counter.errors 1447394143 0 fqdn=li250-160.members.linode.com  ", out);
    point = out.get(0);
    assertEquals("dummy", point.getTable());
    assertEquals("tail.kernel.counter.errors", point.getMetric());
    assertEquals(0.0, point.getValue());
    assertEquals(1447394143000L, point.getTimestamp().longValue());
    assertEquals("li250-160.members.linode.com", point.getHost());

    out = new ArrayList<>();
    decoder.decodeReportPoints(null, "put df.home-ubuntu-efs.df_complex.free 1447985300 9.22337186120781e+18 fqdn=ip-172-20-0-236.us-west-2.compute.internal  ", out);
    point = out.get(0);
    assertEquals("dummy", point.getTable());
    assertEquals("df.home-ubuntu-efs.df_complex.free", point.getMetric());
    assertEquals(9.22337186120781e+18, point.getValue());
    assertEquals(1447985300000L, point.getTimestamp().longValue());
    assertEquals("ip-172-20-0-236.us-west-2.compute.internal", point.getHost());
  }


  @Test
  public void testVersionString() throws Exception {
    ChannelHandlerContext ctx = EasyMock.createMock(ChannelHandlerContext.class);
    List<String> customSourceTags = new ArrayList<String>();
    customSourceTags.add("fqdn");
    OpenTSDBDecoder decoder = new OpenTSDBDecoder("localhost", customSourceTags);
    List<ReportPoint> out = new ArrayList<>();
    EasyMock.expect(ctx.writeAndFlush(EasyMock.anyObject())).andReturn(null);
    EasyMock.replay(ctx);

    decoder.decodeReportPoints(ctx, "version", out);
    assertEquals(0, out.size());
    EasyMock.verify(ctx);
  }
}
