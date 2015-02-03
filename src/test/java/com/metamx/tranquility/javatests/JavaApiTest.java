/*
 * Tranquility.
 * Copyright 2013, 2014, 2015  Metamarkets Group, Inc.
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

package com.metamx.tranquility.javatests;

import backtype.storm.task.IMetricsContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.metamx.common.Granularity;
import com.metamx.tranquility.beam.Beam;
import com.metamx.tranquility.beam.ClusteredBeamTuning;
import com.metamx.tranquility.druid.DruidBeams;
import com.metamx.tranquility.druid.DruidDimensions;
import com.metamx.tranquility.druid.DruidLocation;
import com.metamx.tranquility.druid.DruidRollup;
import com.metamx.tranquility.druid.DruidSpatialDimension;
import com.metamx.tranquility.druid.MultipleFieldDruidSpatialDimension;
import com.metamx.tranquility.druid.SchemalessDruidDimensions;
import com.metamx.tranquility.druid.SpecificDruidDimensions;
import com.metamx.tranquility.storm.BeamBolt;
import com.metamx.tranquility.storm.BeamFactory;
import com.metamx.tranquility.typeclass.JavaObjectWriter;
import com.metamx.tranquility.typeclass.Timestamper;
import com.twitter.finagle.Service;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import junit.framework.Assert;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingCluster;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JavaApiTest
{
  private static final List<String> dimensions = ImmutableList.of("column");
  private static final List<AggregatorFactory> aggregators = ImmutableList.<AggregatorFactory>of(
      new CountAggregatorFactory(
          "cnt"
      )
  );

  public static class MyBeamFactory implements BeamFactory<Map<String, Object>>
  {
    @Override
    public Beam<Map<String, Object>> makeBeam(Map<?, ?> conf, IMetricsContext metrics)
    {
      try (
          final TestingCluster cluster = new TestingCluster(1);
          final CuratorFramework curator = CuratorFrameworkFactory.builder()
                                                                  .connectString(cluster.getConnectString())
                                                                  .retryPolicy(
                                                                      new RetryOneTime(1000)
                                                                  )
                                                                  .build()
      ) {
        cluster.start();
        curator.start();

        final String dataSource = "hey";

        final DruidBeams.Builder<Map<String, Object>> builder = DruidBeams
            .builder(
                new Timestamper<Map<String, Object>>()
                {
                  @Override
                  public DateTime timestamp(Map<String, Object> theMap)
                  {
                    return new DateTime(theMap.get("timestamp"));
                  }
                }
            )
            .curator(curator)
            .discoveryPath("/test/discovery")
            .location(
                DruidLocation.create(
                    "druid:local:indexer",
                    "druid:local:firehose:%s",
                    dataSource
                )
            )
            .rollup(DruidRollup.create(dimensions, aggregators, QueryGranularity.MINUTE))
            .tuning(
                ClusteredBeamTuning.builder()
                                   .segmentGranularity(Granularity.HOUR)
                                   .windowPeriod(new Period("PT10M"))
                                   .build()
            )
            .objectWriter(
                new JavaObjectWriter<Map<String, Object>>()
                {
                  final ObjectMapper objectMapper = new ObjectMapper();

                  @Override
                  public byte[] asBytes(Map<String, Object> obj)
                  {
                    try {
                      return objectMapper.writeValueAsBytes(obj);
                    }
                    catch (JsonProcessingException e) {
                      throw Throwables.propagate(e);
                    }
                  }

                  @Override
                  public byte[] batchAsBytes(Iterator<Map<String, Object>> objects)
                  {
                    try {
                      return objectMapper.writeValueAsBytes(ImmutableList.of(objects));
                    }
                    catch (JsonProcessingException e) {
                      throw Throwables.propagate(e);
                    }
                  }
                }
            );

        final Service<List<Map<String, Object>>, Integer> service = builder.buildJavaService();
        Assert.assertNotNull(service);
        final Beam<Map<String, Object>> beam = builder.buildBeam();
        Assert.assertNotNull(beam);
        return beam;
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  @Test
  public void testSpecificDimensionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.specific(dimensions),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SpecificDruidDimensions);
    Assert.assertEquals("column", ((SpecificDruidDimensions) rollup.dimensions()).dimensions().apply(0));
  }

  @Test
  public void testSchemalessDimensionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.schemaless(),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SchemalessDruidDimensions);
    Assert.assertEquals(0, ((SchemalessDruidDimensions) rollup.dimensions()).dimensionExclusions().size());
  }

  @Test
  public void testSchemalessDimensionsWithExclusionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.schemalessWithExclusions(dimensions),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SchemalessDruidDimensions);
    Assert.assertEquals("column", ((SchemalessDruidDimensions) rollup.dimensions()).dimensionExclusions().apply(0));
  }

  @Test
  public void testSchemalessDimensionsWithExclusionsAndSpatialDimensionsRollupConfiguration() throws Exception
  {
    final DruidRollup rollup = DruidRollup.create(
        DruidDimensions.schemalessWithExclusions(dimensions)
                       .withSpatialDimensions(
                           Lists.newArrayList(
                               DruidSpatialDimension.multipleField(
                                   "coord.geo",
                                   Lists.newArrayList("lat", "lon")
                               )
                           )
                       ),
        aggregators,
        QueryGranularity.MINUTE
    );
    Assert.assertTrue(rollup.dimensions() instanceof SchemalessDruidDimensions);
    Assert.assertEquals("column", ((SchemalessDruidDimensions) rollup.dimensions()).dimensionExclusions().apply(0));
    Assert.assertEquals("coord.geo", ((MultipleFieldDruidSpatialDimension)rollup.dimensions().spatialDimensions().apply(0)).schema().getDimName());
  }

  @Test
  public void testDruidBeamBoltConstruction() throws Exception
  {
    final BeamBolt<Map<String, Object>> beamBolt = new BeamBolt<>(new MyBeamFactory());

    // Ensure serializability
    final ObjectOutputStream objectOutputStream = new ObjectOutputStream(new ByteArrayOutputStream());
    objectOutputStream.writeObject(beamBolt);
    Assert.assertTrue(true);
  }
}
