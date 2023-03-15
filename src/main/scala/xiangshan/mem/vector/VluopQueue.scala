/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import chipsalliance.rocketchip.config.Parameters
import chisel3.{util, _}
import chisel3.util._
import utils._
import utility._
import xiangshan._

class VluopPtr(implicit p: Parameters) extends CircularQueuePtr[VluopPtr](
  p => p(XSCoreParamsKey).VlUopSize
){
}

object VluopPtr {
  def apply(f: Bool, v: UInt)(implicit p: Parameters): VluopPtr = {
    val ptr = Wire(new VluopPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class VluopBundle(implicit p: Parameters) extends XSBundle {
  val rob_idx = UInt(log2Ceil(RobSize).W)
  val wb_dest = UInt(PhyRegIdxWidth.W)//TODO:vector PhyReg
  val lmul = UInt(3.W)
  val dataVMask = Vec(VLEN/8,UInt(1.W))
  val data = Vec(VLEN/8,UInt(8.W))

  def apply(uop: VecOperand, is_pre: Boolean = false, is_allo: Boolean = false, luml: UInt = 0.U) = {
    if (is_pre) {
      this.rob_idx := uop.uop.robIdx.value
      this.lmul := luml
    } else if (is_allo) {
      this.wb_dest := uop.uop.pdest
    } else{
      this.rob_idx := uop.uop.robIdx.value
      this.wb_dest := uop.uop.pdest
      this.lmul := luml
    }
    this
  }
}

class VluopQueueIOBundle(implicit p: Parameters) extends XSBundle {
  val loadRegIn = Vec(2, Flipped(Decoupled(new VecOperand())))
  val loadPipeIn = Vec(exuParameters.LduCnt, Flipped(Decoupled(new VecExuOutput)))
  // TODO: Only one writeback port, Maybe is enough
  val loadWriteback = DecoupledIO(new ExuOutput)//TODO: Maybe need one new port (VExuOutput) or data widen ?
  val vecFeedback = Vec(2,Output(Bool()))
}

class VluopQueue(implicit p: Parameters) extends XSModule with HasCircularQueuePtrHelper
{
  val io = IO(new VluopQueueIOBundle())

  println("LoadUopQueue: size:" + VlUopSize)

  val VluopEntry = Reg(Vec(VlUopSize, new VluopBundle))
  //Fixme
  val Vluop2robEntry = Reg(Vec(VlUopSize, new ExuOutput))
  // For example, an inst -> 4 uops,
  // When first uop comes, 4 entries are all valid and pre_allocated
  val valid = RegInit(VecInit(Seq.fill(VlUopSize)(false.B)))
  val pre_allocated = RegInit(VecInit(Seq.fill(VlUopSize)(false.B)))
  // When an uop really comes, an entry will be allocated
  val allocated = RegInit(VecInit(Seq.fill(VlUopSize)(false.B)))
  // When both data and pdest are readym, this entry is finished
  val finished = RegInit(VecInit(Seq.fill(VlUopSize)(false.B)))

  //First-level buffer
  val buffer_valid_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(false.B)))
  val data_buffer_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(0.U(VLEN.W))))
  val mask_buffer_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(0.U((VLEN/8).W))))
  val rob_idx_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(log2Ceil(RobSize).W))))))
  val rob_idx_valid_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(false.B)))))
  val reg_offset_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(4.W))))))
  val offset_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(4.W))))))
  //val eew_s0 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(4.W))))))
  val uop2rob_buffer_s0 = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new ExuOutput))))
  //Second-level buffer
  val buffer_valid_s1 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(false.B)))))
  val data_buffer_s1 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(VLEN.W))))))
  val mask_buffer_s1 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U((VLEN/8).W))))))
  val rob_idx_valid_s1 = RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(false.B)))))
  val rob_idx_s1 =  RegInit(VecInit(Seq.fill(exuParameters.LduCnt)(VecInit(Seq.fill(2)(0.U(4.W))))))


  for (i <- 0 until 2) {
    io.vecFeedback(i) := Cat((0 until VlUopSize).map(j => io.loadRegIn(i).bits.uop.robIdx.value === VluopEntry(j).rob_idx)).orR
    io.loadRegIn(i).ready := true.B // TODO: should always ready? or count valid_entry?????
  }

  for (i <- 0 until exuParameters.LduCnt) {
    io.loadPipeIn(i).ready := true.B // TODO: should always ready?
  }

  io.loadWriteback.valid := false.B
  io.loadWriteback.bits := DontCare

  val enqPtr = RegInit(0.U.asTypeOf(new VluopPtr))
  val deqPtr = RegInit(0.U.asTypeOf(new VluopPtr))
  val already_in = WireInit(VecInit(Seq.fill(2)(false.B)))
  val enq_valid = WireInit(VecInit(Seq.fill(2)(false.B)))
  for (i <- 0 until 2) {
    already_in(i) := VluopEntry.map(_.rob_idx === io.loadRegIn(i).bits.uop.robIdx.value).reduce(_ || _)
    enq_valid(i) := io.loadRegIn(i).fire && !already_in(i)
  }

  // TODO: How to simplify these codes?
  //  And timing...?
  val inUop = WireInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new VecOperand))))
  var isPer = new Array[Boolean](2)
  when (enq_valid(0) || enq_valid(1)) {
    when(enq_valid(0) && enq_valid(1) && io.loadRegIn(0).bits.uop.robIdx === io.loadRegIn(0).bits.uop.robIdx) {
      enqPtr := enqPtr + io.loadRegIn(0).bits.total_num
      for (i <- 0 until 8) {
        when (i.U < io.loadRegIn(0).bits.total_num) {
          inUop(0).uop.robIdx := io.loadRegIn(0).bits.uop.robIdx - io.loadRegIn(0).bits.inner_idx + i.U
          isPer(0) = !(i.U == io.loadRegIn(0).bits.inner_idx || i.U == io.loadRegIn(1).bits.inner_idx)
          VluopEntry(enqPtr.value + i.U).apply(inUop(0), is_pre = isPer(0), luml = io.loadRegIn(0).bits.lmul)
          valid(enqPtr.value + i.U) := true.B
        }
        if (i.U == io.loadRegIn(0).bits.inner_idx || i.U == io.loadRegIn(1).bits.inner_idx) {
          allocated(enqPtr.value + i.U) := true.B
        } else {
          pre_allocated(enqPtr.value + i.U) := true.B
        }
      }
    }.elsewhen( enq_valid(0) && enq_valid(1) && io.loadRegIn(0).bits.uop.robIdx =/= io.loadRegIn(0).bits.uop.robIdx) {
      enqPtr := enqPtr + io.loadRegIn(0).bits.total_num + io.loadRegIn(1).bits.total_num
      for (i <- 0 until 8) {
        when (i.U < io.loadRegIn(0).bits.total_num) {
          inUop(0).uop.robIdx := io.loadRegIn(0).bits.uop.robIdx - io.loadRegIn(0).bits.inner_idx + i.U
          isPer(0) = !(i.U == io.loadRegIn(0).bits.inner_idx)
          VluopEntry(enqPtr.value + i.U).apply(inUop(0), isPer(0), luml = io.loadRegIn(0).bits.lmul)
          valid(enqPtr.value + i.U) := true.B
        }
        if (i.U == io.loadRegIn(0).bits.inner_idx) {
          allocated(enqPtr.value + i.U) := true.B
        } else {
          pre_allocated(enqPtr.value + i.U) := true.B
        }
      }

      for (i <- 0 until 8) {
        when (i.U < io.loadRegIn(1).bits.total_num) {
          inUop(1).uop.robIdx := io.loadRegIn(1).bits.uop.robIdx - io.loadRegIn(1).bits.inner_idx + i.U
          isPer(1) = !(i.U == io.loadRegIn(1).bits.inner_idx)
          VluopEntry(enqPtr.value + io.loadRegIn(0).bits.total_num + i.U).apply(inUop(1), isPer(1), luml = io.loadRegIn(1).bits.lmul)
          valid(enqPtr.value + io.loadRegIn(0).bits.total_num + i.U) := true.B
        }
        if (i == io.loadRegIn(1).bits.inner_idx) {
          allocated(enqPtr.value + io.loadRegIn(0).bits.total_num + i.U) := true.B
        } else {
          pre_allocated(enqPtr.value + io.loadRegIn(0).bits.total_num + i.U) := true.B
        }
      }
    } .elsewhen (enq_valid(0)) {
      enqPtr := enqPtr + io.loadRegIn(0).bits.total_num
      for (i <- 0 until 8) {
        when (i.U < io.loadRegIn(0).bits.total_num) {
          inUop(0).uop.robIdx := io.loadRegIn(0).bits.uop.robIdx - io.loadRegIn(0).bits.inner_idx + i.U
          isPer(0) = !(i.U == io.loadRegIn(0).bits.inner_idx)
          VluopEntry(enqPtr.value + i.U).apply(inUop(0), is_pre = isPer(0), luml = io.loadRegIn(0).bits.lmul)
          valid(enqPtr.value + i.U) := true.B
        }
        if (i == io.loadRegIn(0).bits.inner_idx) {
          allocated(enqPtr.value + i.U) := true.B
        } else {
          pre_allocated(enqPtr.value + i.U) := true.B
        }
      }
    } .elsewhen (enq_valid(1)) {
      enqPtr := enqPtr + io.loadRegIn(1).bits.total_num
      for (i <- 0 until 8) {
        when (i.U < io.loadRegIn(1).bits.total_num) {
          inUop(1).uop.robIdx := io.loadRegIn(1).bits.uop.robIdx - io.loadRegIn(1).bits.inner_idx + i.U
          isPer(1) = !(i.U == io.loadRegIn(1).bits.inner_idx)
          VluopEntry(enqPtr.value + i.U).apply(inUop(1), isPer(1), luml = io.loadRegIn(1).bits.lmul)
          valid(enqPtr.value + i.U) := true.B
        }
        if (i == io.loadRegIn(1).bits.inner_idx) {
          allocated(enqPtr.value + i.U) := true.B
        } else {
          pre_allocated(enqPtr.value + i.U) := true.B
        }
      }
    }
  } .elsewhen (!enq_valid(0) && io.loadRegIn(0).fire || !enq_valid(1) &&io.loadRegIn(1).fire) {
    when (!enq_valid(0) && io.loadRegIn(0).fire) {
      val debug_hit = WireInit(VecInit(Seq.fill(VlUopSize)(false.B))) // for debug
      for (i <- 0 until VlUopSize) {
        when (VluopEntry(i).rob_idx === io.loadRegIn(0).bits.uop.robIdx.value && valid(i) && pre_allocated(i)) {
          VluopEntry(i).apply(io.loadRegIn(0).bits, is_allo = true)
          pre_allocated(i) := false.B
          debug_hit(i) := true.B
        }
        assert(PopCount(debug_hit) <= 1.U, "VluopQueue Multi-Hit!")
      }
    }
    when (!enq_valid(1) && io.loadRegIn(1).fire) {
      val debug_hit = WireInit(VecInit(Seq.fill(VlUopSize)(false.B))) // for debug
      for (i <- 0 until VlUopSize) {
        when (VluopEntry(i).rob_idx === io.loadRegIn(1).bits.uop.robIdx.value && valid(i) && pre_allocated(i)) {
          VluopEntry(i).apply(io.loadRegIn(1).bits, is_allo = true)
          pre_allocated(i) := false.B
          debug_hit(i) := true.B
        }
        assert(PopCount(debug_hit) <= 1.U, "VluopQueue Multi-Hit!")
      }
    }
  }

  for (entry <- 0 until VlUopSize) {
    when (VluopEntry(entry).lmul(2) === 0.U ) {
     finished(entry) := valid(entry) && allocated(entry) && VluopEntry(entry).dataVMask.asUInt.orR
    }.elsewhen (VluopEntry(entry).lmul === "b111".U) { // 1/2
      finished(entry) := valid(entry) && allocated(entry) && VluopEntry(entry).dataVMask.asUInt(7,0).orR
    }.elsewhen(VluopEntry(entry).lmul === "b110".U) { // 1/4
      finished(entry) := valid(entry) && allocated(entry) && VluopEntry(entry).dataVMask.asUInt(3,0).orR
    }.elsewhen(VluopEntry(entry).lmul === "b101".U) { // 1/8
      finished(entry) := valid(entry) && allocated(entry) && VluopEntry(entry).dataVMask.asUInt(1,0).orR
    }
  }

  // write data from loadpipe to first_level buffer
  for (i <- 0 until exuParameters.LduCnt) {
    when (io.loadPipeIn(i).fire) {
      buffer_valid_s0(i)  := true.B  //TODO: condition
      data_buffer_s0(i)   := io.loadPipeIn(i).bits.data
      mask_buffer_s0(i)   := io.loadPipeIn(i).bits.mask
      rob_idx_s0(i)       := io.loadPipeIn(i).bits.rob_idx
      rob_idx_valid_s0(i) := io.loadPipeIn(i).bits.rob_idx_valid
      reg_offset_s0(i)    := io.loadPipeIn(i).bits.reg_offset
      offset_s0(i)        := io.loadPipeIn(i).bits.offset
    }
  }
  // write data from first_level buffer to second_level buffer
  for (i <- 0 until exuParameters.LduCnt) {
    when (buffer_valid_s0(i) === true.B) {
      rob_idx_valid_s1(i) := rob_idx_valid_s0(i)
      rob_idx_s1(i)       := rob_idx_s0(i)
      buffer_valid_s1(i).map(_ := buffer_valid_s0(i))
      mask_buffer_s1(i)   := VecGenMask(rob_idx_valid = rob_idx_valid_s0(i), reg_offset = reg_offset_s0(i), offset = offset_s0(i), mask = mask_buffer_s0(i))
      data_buffer_s1(i)   := VecGenData(rob_idx_valid = rob_idx_valid_s0(i), reg_offset = reg_offset_s0(i), offset = offset_s0(i), data = data_buffer_s0(i))
    }
  }

  //write data from second_level buffer to VluopEntry
  for (i <- 0 until exuParameters.LduCnt) {
    for (j <- 0 until 2) {
      when (buffer_valid_s1(i)(j) === true.B && rob_idx_valid_s1(i)(j) === true.B) {
        for (entry <- 0 until VlUopSize) {
          when (rob_idx_s1(i)(j) === VluopEntry(entry).rob_idx) {
            for (k <- 0 until VLEN/8) {
              when (mask_buffer_s1(i)(j)(k)) {
                VluopEntry(entry).data(k) := data_buffer_s1(i)(j)(k*8 + 7,k*8)
                VluopEntry(entry).dataVMask(k) := mask_buffer_s1(i)(j)(k)
              }
            }
          }
        }
      }
    }
  }

  //writeback to regfile/rob
  val deq_index = deqPtr.value
  when (valid(deq_index) && finished(deq_index)) {//TODO:need optimization?
    io.loadWriteback.valid := true.B
    io.loadWriteback.bits := RegEnable(Vluop2robEntry(deq_index),io.loadWriteback.fire)
    io.loadWriteback.bits.data := RegEnable(VluopEntry(deq_index).data.asUInt,io.loadWriteback.fire)
    io.loadWriteback.bits.uop.pdest :=  RegEnable(VluopEntry(deq_index).wb_dest,io.loadWriteback.fire)
    finished(deq_index) := RegEnable(false.B,io.loadWriteback.fire)
    valid(deq_index) := RegEnable(false.B,io.loadWriteback.fire)

    when(io.loadWriteback.fire) {
      deqPtr := deqPtr + 1.U
    }
  }

}