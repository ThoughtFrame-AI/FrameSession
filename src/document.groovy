package assets

import org.openedit.event.WebEvent
import org.thoughtframe.dsl.FrameDsl

class DocToKnowledge extends FrameDsl {

	DocToKnowledge() {
		fsm {
			
			props{
				assetid{					
					name="AssetID"
				}
				currentpage{
					name="Current Page Number"
				}
				state{
					name="State"
				}
				
			}
			
			state("init") {
				listen("thought:*:${framepath}")				
				transition("analyze")
			}

			state("analyze") {
				interval("1s")
				action("actions/scanpdf" ,[assetid: assetid, pagenumber:currentpage])
				action("actions/buildembedding" ,[assetid: assetid, pagenumber:currentpage])
				action("actions/precache" ,[assetid: assetid, pagenumber:currentpage])				
				action("actions/nextpage" ,[assetid: assetid, pagenumber:currentpage]){
					on("nextpage"){ 
						capture([currentpage:"pagenumber"])
					}		
					on("lastpage"){ 
						transition("done")
					}
				}
				exception{}
			}
			state("done") {
				log("Done processing PDF Pages")
			}
		}
	}
}
