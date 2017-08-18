package logic

/**
  * Created by federico on 16/08/17.
  */

object Scene {
  import LiftAST._

  /*A scene node is any conceptual element that can be transformed into a series of graphical primitives.
   * The idea is that each element in a Lift expression can be represented as one or more of these elements.
   * Ultimately, the entire expression corresponds to a single root-node. The Scene tree is then `drawn` by transforming
   * it into a series of abstract graphics primitives
  */
  sealed trait Node

  sealed trait TypeNode extends Node

  case class FloatNode() extends TypeNode
  sealed trait ArrayTypeNode extends TypeNode
  case class LinearArrayNode(element:TypeNode, size:Int) extends ArrayTypeNode
  case class GridArrayNode(elementType: TypeNode, width:Int, height:Int) extends ArrayTypeNode

  sealed trait OperationNode extends Node
  case class MapNode(inputElement:TypeNode, outputElement:TypeNode, size:ArraySize) extends OperationNode

  val MAP_NODE_CHILDREN_DISTANCE = 5

  private def nodeWidth(node: Node):Int = node match {
    case FloatNode() => 1
    case LinearArrayNode(elem, size) => nodeWidth(elem) * size
    case GridArrayNode(elem, width, _) => nodeWidth(elem) * width
    case MapNode(input, output, size) => Math.max(nodeWidth(input) + size, nodeWidth(output) + size)
  }

  private def nodeHeight(node: Node):Int = node match {
    case FloatNode() => 1
    case LinearArrayNode(elem, size) => nodeHeight(elem)
    case GridArrayNode(elem, _, height) => nodeHeight(elem) * height
    case MapNode(input, output, size) => nodeHeight(input) +  MAP_NODE_CHILDREN_DISTANCE + nodeHeight(output)
  }

  //Node construction (from lift source items)

  def typeNode(t:Type):TypeNode = t match {
    case Float => FloatNode()
    case array:Array =>
      //Get the nested array sizes as an ordered list
      val sizes = flattenArraySizes(array)
      //Group the ordered list of sizes according to the default dimension rules
      val groupedSizes = groupSizesByDimensions(defaultDimensionSplits(sizes.length), sizes)
      //The ultimate non-array element contained in the nested array
      val bottomElement = arrayBottomElementType(array)
      arrayTypeNode(bottomElement, groupedSizes)
    case _:Function => throw new NotImplementedError("No support for drawing function types yet")
  }

  private def arrayTypeNode(bottomT:Type, sizes:List[List[ArraySize]]):ArrayTypeNode = {
    if(sizes.isEmpty) {
      throw new Exception("Array type renderer with empty sizes - impossible!!")
    }
    val(currentSizes::nextSizes) = sizes
    //Build the contained element first...
    val inner = nextSizes match {
      //If we are out of sizes, then this is the end of the array. we contain the bottom element
      case Nil => typeNode(bottomT)
      //Otherwise recurse
      case _ => arrayTypeNode(bottomT, nextSizes)
    }
    //Now build the current level of array
    currentSizes.length match {
      //1 dimension - linear array. Dimension is length
      case 1 => LinearArrayNode(inner, currentSizes.head)
      //2 dimensions - grid. Dimensions are width and height
      case 2 => GridArrayNode(inner, currentSizes.head, currentSizes.tail.head)
      //any other - not supported yet!
      case n => throw new Exception(s"Unsupported rendering of $n-dimensional array level. Try another dimension grouping")
    }
  }

  def operationNode(operation:Operation):OperationNode = {
    operation match {
      case Map(fType, size) => MapNode(typeNode(fType.inputType), typeNode(fType.outputType), size)
    }
  }


  private def flattenArraySizes(array:Array):List[ArraySize] =
    array.size::(array.elementT match {
      case nested:Array => flattenArraySizes(nested)
      case _ => List()
    })

  private def defaultDimensionSplits(n:Int):List[Int] = n match {
    case 0 => Nil
    case 1 => List(1)
    case 2 => List(2)
    case _ => 2::defaultDimensionSplits(n - 2)
  }


  private def groupSizesByDimensions(dimensions:List[Int], sizes:List[ArraySize]):List[List[Int]] = {
    dimensions match {
      case Nil => List()
      case dim::other_dims => sizes.take(dim) ::groupSizesByDimensions(other_dims, sizes.drop(dim))
    }
  }

  import Graphics._

  //The methods here take care of transforming nodes into sets of graphical primitives.
  def drawType(typeNode: TypeNode):Iterable[GraphicalPrimitive] = {
    typeNode match {
      case FloatNode() => Seq(Rectangle(0, 0, 1, 1))
      case LinearArrayNode(element, size) =>
        val elemWidth = nodeWidth(element)
        //compute inner element primitives
        val elementPrims = drawType(element)
        //Repeat each set of primitives up to size times, translating the set by the
        //position
        val sets = (0 until size).map(pos => translateAll(elementPrims, dx = pos*elemWidth, dy = 0))
        //As a final results, flatten the sets and add the container box
        sets.flatten ++ Seq(Box(0, 0, size*elemWidth, 1))
      case GridArrayNode(elementType, width, height) =>
        val elemWidth =nodeWidth(elementType)
        val elemHeight = nodeHeight(elementType)
        //compute inner element primitives
        val elementPrims = drawType(elementType)
        //Compute the positions where the children will go
        val positions = for(x <- 0 until width;
                            y <- 0 until height) yield (x*elemWidth, y*elemHeight)
        //For each position, replicate the elementPrimitives and translate them to that place
        val sets = positions.map{case (x,y) => translateAll(elementPrims, x, y)}
        //Flatten the sets and wrap in container box
        sets.flatten ++ Seq(Box(0, 0, width*elemWidth, height*elemHeight))
    }
  }

  def drawOperation(operationRenderer: OperationNode):Iterable[GraphicalPrimitive] = {
    operationRenderer match {
      case MapNode(inType, outType, size) =>
        //First render the input type
        val inputArray = LinearArrayNode(inType, size)
        val outputArray = LinearArrayNode(outType, size)
        val inputPrimitives = drawType(inputArray)
        //Translate the output primitives under the input primitives
        val outputPrimitives = translateAll(
          drawType(outputArray),
          dx = 0,
          dy = nodeHeight(inputArray) + MAP_NODE_CHILDREN_DISTANCE
        )
        //Make the arrow from input array to output array
        val arrowX = Math.max(nodeWidth(inputArray)/2, nodeWidth(outputArray)/2)
        val arrowStartY = nodeHeight(inputArray)
        val arrowEndY = arrowStartY + MAP_NODE_CHILDREN_DISTANCE
        val arrow = Arrow(arrowX, arrowStartY, arrowX, arrowEndY)
        //Problem:at this point, inputPrimitives and output primitives are not centered.
        //Need to center the primitives.
        val alignments = horizontalAlignment(Seq(inputArray, outputArray))
        val centeredInputPrims = translateAll(inputPrimitives, dx = alignments(inputArray), dy = 0)
        val centeredOutputPrims = translateAll(outputPrimitives, dx = alignments(outputArray), dy = 0)
        centeredInputPrims ++ centeredOutputPrims ++ Seq(arrow)
    }
  }
  /***
    * Computes horizontal translations needed for each node to be aligned on a common
    * center axis.
    * @param nodes The nodes to align
    * @return A mapping from each node to the amount of horizontal translation needed to align
    */
  private def horizontalAlignment(nodes:Iterable[Node]):scala.collection.Map[Node,Int] = {
    val nodeWidthMap = nodes.map(node => (node, nodeWidth(node))).toMap
    val maxWidth = nodeWidthMap.values.max
    //For each node, we need to translate by (maxWidth - nodeWidth)/2
    nodeWidthMap.mapValues(x => (maxWidth - x)/2)
  }
}