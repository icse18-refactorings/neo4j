/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.pipes.aggregation

import org.neo4j.cypher.commands.ReturnItem
import org.neo4j.cypher.{SyntaxError, Comparer}
import java.lang.Boolean

trait MinMax extends AggregationFunction with Comparer {
  val returnItem: ReturnItem

  def keep(comparisonResult: Int): Boolean

  private var biggestSeen: Any = null

  def result: Any = biggestSeen

  def apply(data: Map[String, Any]) {
    val value = returnItem(data).head._2

    value match {
      case null =>
      case x: Number => checkIfLargest(value)
      case _ => throw new SyntaxError("MIN/MAX can only handle values of Number type, or null.")
    }
  }

  private def checkIfLargest(value: Any) {
    if (biggestSeen == null) {
      biggestSeen = value
    } else if (keep(compare(biggestSeen, value))) {
      biggestSeen = value
    }
  }
}

class MaxFunction(val returnItem: ReturnItem) extends AggregationFunction with MinMax {
  def keep(comparisonResult: Int) = comparisonResult < 0
}

class MinFunction(val returnItem: ReturnItem) extends AggregationFunction with MinMax {
  def keep(comparisonResult: Int) = comparisonResult > 0
}