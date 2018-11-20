package sigmastate.serialization

import scapi.sigma.DLogProtocol.ProveDlog
import sigmastate.SGroupElement
import sigmastate.Values.{SigmaBoolean, Value}
import sigmastate.lang.Terms._
import sigmastate.serialization.OpCodes.OpCode
import sigmastate.utils.{SigmaByteReader, SigmaByteWriter}
import scorex.util.Extensions._

case class ProveDlogSerializer(cons: Value[SGroupElement.type] => SigmaBoolean)
  extends ValueSerializer[ProveDlog] {

  override val opCode: OpCode = OpCodes.ProveDlogCode

  override def serialize(obj: ProveDlog, w: SigmaByteWriter): Unit =
    w.putValue(obj.value)

  override def parse(r: SigmaByteReader): SigmaBoolean =
    cons(r.getValue().asValue[SGroupElement.type])
}
